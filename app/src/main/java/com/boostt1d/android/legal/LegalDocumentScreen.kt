package com.boostt1d.android.legal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.boostt1d.android.ui.BoostDivider
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme

/**
 * A legal document, full screen and scrollable.
 *
 * Full screen rather than a dialog on purpose: these run to a couple of thousand words,
 * and a cramped scroll area is the shape that teaches people not to read them.
 */
@Composable
fun LegalDocumentDialog(document: LegalText.Document, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val colors = BoostTheme.colors
        Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = BoostSpacing.lg, vertical = BoostSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            document.title,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        )
                        Text(
                            "Last updated ${document.lastUpdated}",
                            fontSize = 12.sp,
                            color = colors.textTertiary,
                        )
                    }
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = colors.textPrimary,
                        modifier = Modifier
                            .size(26.dp)
                            .clickable(onClick = onDismiss),
                    )
                }

                BoostDivider()

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(BoostSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(BoostSpacing.lg),
                ) {
                    items(document.sections.size) { index ->
                        val section = document.sections[index]
                        Column(verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                            Text(
                                section.title,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                            )
                            Text(
                                section.body,
                                fontSize = 15.sp,
                                lineHeight = 22.sp,
                                color = colors.textSecondary,
                            )
                            section.bullets.forEach { bullet ->
                                Row(horizontalArrangement = Arrangement.spacedBy(BoostSpacing.xs)) {
                                    Text("•", fontSize = 15.sp, color = colors.textSecondary)
                                    Text(
                                        bullet,
                                        fontSize = 15.sp,
                                        lineHeight = 22.sp,
                                        color = colors.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
