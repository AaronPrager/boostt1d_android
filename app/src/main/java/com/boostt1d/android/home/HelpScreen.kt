package com.boostt1d.android.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.logs.ScreenScaffold
import com.boostt1d.android.ui.BoostCard
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme

/**
 * Step-by-step setup and usage guides. Ported from the iOS HelpView.
 *
 * A chapter opens in place rather than as a pushed screen, so the bottom bar stays put and
 * Back returns to the list before it leaves the screen.
 */
@Composable
fun HelpScreen(onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    var openChapter by remember { mutableStateOf<HelpChapter?>(null) }

    openChapter?.let { chapter ->
        BackHandler { openChapter = null }
        HelpChapterScreen(chapter, onBack = { openChapter = null }, onOpenUrl = onOpenUrl, modifier = modifier)
        return
    }

    val colors = BoostTheme.colors
    ScreenScaffold(title = "Help", subtitle = "Setup guides", modifier = modifier) {
        item {
            Text(
                "Step-by-step guides for connecting data sources and using BoostT1D. For a " +
                    "quick overview, open How It Works.",
                fontSize = 14.sp, color = colors.textSecondary,
                modifier = Modifier.padding(bottom = BoostSpacing.xs),
            )
        }

        item { SectionHeading("Connect your data") }
        items(HelpContent.chapters.filter { it.isConnectionGuide }) { chapter ->
            ChapterRow(chapter) { openChapter = chapter }
        }

        item { SectionHeading("Use the app") }
        items(HelpContent.chapters.filter { !it.isConnectionGuide }) { chapter ->
            ChapterRow(chapter) { openChapter = chapter }
        }
    }
}

@Composable
private fun SectionHeading(text: String) = Text(
    text.uppercase(),
    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BoostTheme.colors.textTertiary,
    modifier = Modifier.padding(top = BoostSpacing.sm, bottom = BoostSpacing.xxs),
)

@Composable
private fun ChapterRow(chapter: HelpChapter, onOpen: () -> Unit) {
    val colors = BoostTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(BoostRadius.lg))
            .clickable(onClick = onOpen)
            .padding(BoostSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(chapterIcon(chapter.id), contentDescription = null, tint = chapterTint(chapter.id), modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(chapter.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Text(chapter.subtitle, fontSize = 12.sp, color = colors.textSecondary)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary)
    }
}

@Composable
private fun HelpChapterScreen(
    chapter: HelpChapter,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors
    val tint = chapterTint(chapter.id)

    ScreenScaffold(title = null, subtitle = null, modifier = modifier) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                }
                Text("Help", fontSize = 15.sp, color = colors.textSecondary)
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = BoostSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(52.dp).background(tint.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(chapterIcon(chapter.id), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(chapter.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                    Text(chapter.subtitle, fontSize = 14.sp, color = colors.textSecondary)
                }
            }
        }

        item {
            BoostCard {
                chapter.steps.forEachIndexed { index, step ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = BoostSpacing.xxs),
                        horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
                    ) {
                        Box(
                            modifier = Modifier.size(26.dp).background(tint, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${index + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xxs)) {
                            Text(step.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                            Text(step.detail, fontSize = 14.sp, color = colors.textSecondary)
                        }
                    }
                }
            }
        }

        chapter.note?.let { note ->
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.primary.copy(alpha = 0.08f), RoundedCornerShape(BoostRadius.md))
                        .padding(BoostSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                    Text(note, fontSize = 13.sp, color = colors.textSecondary)
                }
            }
        }

        if (chapter.linkLabel != null && chapter.linkUrl != null) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface, RoundedCornerShape(BoostRadius.md))
                        .clickable { onOpenUrl(chapter.linkUrl) }
                        .padding(BoostSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        chapter.linkLabel, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.primary, modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

private fun chapterIcon(id: String): ImageVector = when (id) {
    "connect-nightscout" -> Icons.Filled.Cloud
    "connect-dexcom" -> Icons.Filled.Wifi
    "connect-libre" -> Icons.Filled.Sensors
    "manual" -> Icons.Filled.TouchApp
    "view-data" -> Icons.Filled.Favorite
    "food-photos" -> Icons.Filled.CameraAlt
    "event-log" -> Icons.AutoMirrored.Filled.MenuBook
    "insights" -> Icons.Filled.Insights
    "reports" -> Icons.Filled.MedicalServices
    else -> Icons.Filled.Settings
}

@Composable
private fun chapterTint(id: String): Color {
    val colors = BoostTheme.colors
    return when (id) {
        "connect-nightscout" -> colors.primary
        "connect-dexcom" -> colors.high
        "connect-libre" -> colors.veryHigh
        "manual" -> colors.insight
        "view-data" -> colors.low
        "food-photos" -> colors.inRange
        "event-log" -> colors.insight
        "insights" -> colors.high
        "reports" -> colors.clinical
        else -> colors.neutral
    }
}
