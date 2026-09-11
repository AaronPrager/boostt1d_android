package com.boostt1d.android.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme

/** One page of the product tour. */
private data class TutorialPage(
    val icon: ImageVector,
    val tint: Color,
    val eyebrow: String,
    val title: String,
    val message: String,
    val highlights: List<String>,
    val tip: String?,
)

/**
 * The product tour, nine pages. Ported from the iOS TutorialView.
 *
 * Reachable from Menu at any time rather than only at first launch, so someone who skipped
 * it during setup can still find out what the app does.
 */
@Composable
fun TutorialScreen(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BoostTheme.colors
    val pages = remember { pages(colors.primary, colors.high, colors.inRange, colors.insight, colors.low, colors.clinical) }
    var index by remember { mutableIntStateOf(0) }
    val page = pages[index]
    val isLast = index == pages.lastIndex

    Box(
        modifier = modifier.fillMaxSize().background(colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = BoostSpacing.lg),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1} of ${pages.size}",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (!isLast) TextButton(onClick = onFinished) { Text("Skip", color = colors.textSecondary) }
            }

            LinearProgressIndicator(
                progress = { (index + 1f) / pages.size },
                color = page.tint,
                trackColor = colors.surfaceMuted,
                modifier = Modifier.fillMaxWidth().padding(bottom = BoostSpacing.xs),
            )

            AnimatedContent(
                targetState = index,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "tutorial-page",
                modifier = Modifier.weight(1f),
            ) { shown ->
                PageBody(pages[shown])
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = BoostSpacing.md),
                verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(
                    onClick = { if (isLast) onFinished() else index += 1 },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(BoostRadius.md),
                    colors = ButtonDefaults.buttonColors(containerColor = page.tint),
                ) {
                    Text(if (isLast) "Get Started" else "Next", fontWeight = FontWeight.SemiBold)
                    if (!isLast) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                            modifier = Modifier.padding(start = BoostSpacing.xs).size(18.dp),
                        )
                    }
                }
                if (!isLast) TextButton(onClick = onFinished) { Text("Skip tour", color = colors.textSecondary) }
            }
        }
    }
}

@Composable
private fun PageBody(page: TutorialPage) {
    val colors = BoostTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .padding(top = BoostSpacing.sm)
                .size(96.dp)
                .background(page.tint.copy(alpha = 0.15f), CircleShape)
                .border(2.dp, page.tint.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(page.icon, contentDescription = null, tint = page.tint, modifier = Modifier.size(44.dp))
        }

        Text(page.eyebrow.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = page.tint)
        Text(
            page.title, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            color = colors.textPrimary, textAlign = TextAlign.Center,
        )
        Text(page.message, fontSize = 15.sp, color = colors.textSecondary, textAlign = TextAlign.Center)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(BoostRadius.lg))
                .border(1.dp, page.tint.copy(alpha = 0.2f), RoundedCornerShape(BoostRadius.lg))
                .padding(BoostSpacing.md),
            verticalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
        ) {
            page.highlights.forEach { highlight ->
                Row(horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = page.tint, modifier = Modifier.size(16.dp))
                    Text(highlight, fontSize = 14.sp, color = colors.textPrimary)
                }
            }
        }

        page.tip?.let { tip ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.high.copy(alpha = 0.12f), RoundedCornerShape(BoostRadius.md))
                    .padding(BoostSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
            ) {
                Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = colors.high, modifier = Modifier.size(16.dp))
                Text(tip, fontSize = 12.sp, color = colors.textSecondary)
            }
        }
    }
}

private fun pages(
    primary: Color, high: Color, inRange: Color, insight: Color, low: Color, clinical: Color,
): List<TutorialPage> = listOf(
    TutorialPage(
        Icons.Filled.Favorite, primary, "Welcome", "Your T1D companion",
        "BoostT1D helps you see glucose, meals, and patterns in one place, so everyday data " +
            "is easier to understand and share with your care team.",
        listOf(
            "Built for people living with Type 1 diabetes",
            "Educational tools, not a medical device",
            "Works with Nightscout, Dexcom Share, FreeStyle Libre, or manual entry",
        ),
        "You can replay this tour anytime from How It Works in the Menu.",
    ),
    TutorialPage(
        Icons.Filled.Wifi, high, "Step 1", "Connect your glucose",
        "Pick the source that fits you. Switch later in Data Source without starting over.",
        listOf(
            "Nightscout, sync from your own Nightscout site",
            "Dexcom Share, use your Dexcom app credentials",
            "FreeStyle Libre, read through a LibreLinkUp follower account",
            "Manual, enter readings yourself and still use every feature",
        ),
        "Need setup steps? Open Help from the Menu.",
    ),
    TutorialPage(
        Icons.Filled.Dashboard, primary, "Home", "Your Dashboard",
        "This is mission control: latest glucose, trend, and shortcuts to everything else.",
        listOf(
            "See current glucose and how it is moving",
            "Active insulin and carbs still on board",
            "The last 24 hours of glucose as a trend line",
        ),
        "Dashboard, Food, Insights, Logs and Menu sit in the bottom bar. Snap a Meal lives under Food.",
    ),
    TutorialPage(
        Icons.Filled.CameraAlt, inRange, "Meals", "Snap a Meal",
        "Photograph your food for a quick informational carb estimate, then save it so " +
            "nothing gets lost.",
        listOf(
            "Take a clear photo of the plate or package",
            "Review and adjust the estimate before saving",
            "Saved meals live in Food Log for later review",
        ),
        "Estimates can be wrong. They are for awareness, not dosing decisions.",
    ),
    TutorialPage(
        Icons.Filled.ListAlt, insight, "Logging", "Event Log and Food Log",
        "Build a timeline of what happened. Better logs mean clearer patterns when you look back.",
        listOf(
            "Event Log, insulin, corrections, exercise, and more",
            "Food Log, browse meals and carbs, including carbs downloaded from Nightscout",
            "Anything you enter by hand can be edited later",
        ),
        "Accurate times matter: What Happened? uses when things happened, not just what.",
    ),
    TutorialPage(
        Icons.Filled.ShowChart, low, "Trends", "See your history",
        "BG Log turns readings into charts so you can spot highs, lows and repeating shapes.",
        listOf(
            "Browse past readings and trend charts",
            "Target range comes from your Profile settings",
            "Multi-day views appear as more data accumulates",
        ),
        null,
    ),
    TutorialPage(
        Icons.Filled.Insights, high, "Patterns", "One seven-day review",
        "What Happened? connects glucose, meals, insulin, exercise, patterns and therapy " +
            "settings across seven completed days.",
        listOf(
            "Patterns, recurring glucose behavior by time of day",
            "Therapy, formula-verified setting discussion points",
            "One optional AI explanation pass per day; the formula result is always there",
        ),
        "Complete meal, insulin and exercise logs make the seven-day review more specific.",
    ),
    TutorialPage(
        Icons.Filled.MedicalServices, clinical, "Appointments", "Doctor Visit report",
        "Walk into appointments with a clean 7 or 14 day summary instead of scrolling raw charts.",
        listOf(
            "Pick a report window that matches your visit",
            "Review highlights before you share or export",
            "Export a PDF and share it however you like",
        ),
        null,
    ),
    TutorialPage(
        Icons.Filled.Verified, inRange, "You're ready", "Start with one small win",
        "You don't need to use everything today. Try one path: Snap a Meal, add an Event Log " +
            "entry, or just check your Dashboard.",
        listOf(
            "Quick actions are on the Dashboard",
            "Help has step-by-step setup guides",
            "Support BoostT1D if you want to fund future features",
        ),
        "Questions? Email info@boostt1d.com or visit boostt1d.com.",
    ),
)
