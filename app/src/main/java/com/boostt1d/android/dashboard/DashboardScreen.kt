package com.boostt1d.android.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.data.GlucoseDisplay
import com.boostt1d.android.data.GlucoseSettings
import com.boostt1d.android.data.PhotoScaling
import com.boostt1d.android.data.UserProfile
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme

/**
 * Where you are now — except there is nothing to show yet.
 *
 * The iOS dashboard carries current glucose with trend, time in range, insulin on
 * board and today's carbs. All of that needs readings, and this build has no way to
 * receive any: manual entry is the only source and its logging screens are not
 * ported. So the screen states that plainly and offers the one thing that does work.
 */
@Composable
fun DashboardScreen(
    profile: UserProfile,
    settings: GlucoseSettings,
    onOpenProfile: () -> Unit,
) {
    val colors = BoostTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(BoostSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(BoostSpacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
        ) {
            Avatar(profile, onClick = onOpenProfile)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "DASHBOARD",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary,
                )
                Text(
                    profile.name.ifBlank { "Welcome" },
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
            }
        }

        BoostCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
            ) {
                Icon(
                    Icons.Filled.Timeline,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    "No readings yet",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
            }
            Text(
                "You're set up for manual entry, so nothing arrives on its own. Logging and " +
                    "charts are not built yet — for now this screen stays empty on purpose.",
                fontSize = 14.sp,
                color = colors.textSecondary,
            )
        }

        BoostCard {
            Text(
                "YOUR TARGET RANGE",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
            )
            Text(
                "${GlucoseDisplay.format(settings.lowGlucose, profile.bgUnit)} – " +
                    "${GlucoseDisplay.format(settings.highGlucose, profile.bgUnit)} " +
                    profile.bgUnit.displayName,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = colors.inRange,
            )
        }

        Box(modifier = Modifier.weight(1f))

        Button(
            onClick = onOpenProfile,
            shape = RoundedCornerShape(BoostRadius.md),
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                modifier = Modifier.padding(end = BoostSpacing.xs).size(20.dp),
            )
            Text("Profile", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Avatar(profile: UserProfile, onClick: () -> Unit) {
    val colors = BoostTheme.colors
    val bitmap = remember(profile.photoData) { PhotoScaling.decodeAvatar(profile.photoData) }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(colors.surfaceMuted)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Profile",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = profile.name.trim().take(1).uppercase().ifEmpty { "?" },
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
