package com.boostt1d.android.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.Config
import com.boostt1d.android.logs.ScreenScaffold
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSoftIcon
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme

/**
 * The optional donation page. Ported from the iOS SupportBoostT1DView.
 *
 * The app takes no money of its own: the button opens the support page in a browser and
 * Stripe handles the checkout there, so nothing about a card ever reaches this process.
 */
@Composable
fun SupportScreen(onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = BoostTheme.colors

    ScreenScaffold(title = "Support", subtitle = "Optional donation", modifier = modifier) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = BoostSpacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
            ) {
                BoostSoftIcon(Icons.Filled.Favorite, colors.low, size = 72.dp)
                Text(
                    "Support BoostT1D Development",
                    fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = colors.textPrimary, textAlign = TextAlign.Center,
                )
                Text(
                    "BoostT1D is free to use. Optional contributions help cover AI and hosting " +
                        "so we can keep improving the app for the T1D community.",
                    fontSize = 15.sp, color = colors.textSecondary, textAlign = TextAlign.Center,
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = BoostSpacing.sm)
                    .background(colors.primary, RoundedCornerShape(BoostRadius.md))
                    .clickable { onOpenUrl(Config.SUPPORT_PAGE_URL) }
                    .padding(horizontal = BoostSpacing.md, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text(
                    "Donate", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    modifier = Modifier.weight(1f).padding(start = BoostSpacing.xs),
                )
                Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }

        item {
            Text(
                "You'll choose your amount on Stripe's secure checkout page. BoostT1D never " +
                    "sees your card details. Contributions are not tax-deductible.",
                fontSize = 12.sp, color = colors.textSecondary,
                modifier = Modifier.padding(vertical = BoostSpacing.xs),
            )
        }

        item {
            BoostCard {
                Text("What your support funds", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                ImpactRow(
                    Icons.Filled.Handyman, "Ship the next features",
                    "Help fund smarter insights, better food tools, and peer connections.",
                )
                ImpactRow(
                    Icons.Filled.Storage, "Keep tools available",
                    "Contributions help cover hosting, AI costs, and ongoing work.",
                )
                ImpactRow(
                    Icons.Filled.Groups, "Grow peer support",
                    "Help more newly diagnosed families and teens find someone who understands.",
                )
            }
        }
    }
}

@Composable
private fun ImpactRow(icon: ImageVector, title: String, description: String) {
    val colors = BoostTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = BoostSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
    ) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xxs)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Text(description, fontSize = 14.sp, color = colors.textSecondary)
        }
    }
}
