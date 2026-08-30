package com.boostt1d.android.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boostt1d.android.ui.BoostRadius
import com.boostt1d.android.ui.BoostSpacing
import com.boostt1d.android.ui.BoostTheme

/**
 * Title, optional add button, scrolling content.
 *
 * Shared so every log screen has the same header and the same distance above the bottom
 * bar — the bar floats over the content, so the last row needs room or it hides beneath it.
 */
@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    onAdd: (() -> Unit)? = null,
    addLabel: String = "Add",
    content: LazyListScope.() -> Unit,
) {
    val colors = BoostTheme.colors

    Box(
        modifier = modifier.fillMaxSize().background(colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
    Column(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BoostSpacing.lg, vertical = BoostSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                subtitle?.let { Text(it, fontSize = 14.sp, color = colors.textSecondary) }
            }

            if (onAdd != null) {
                Button(
                    onClick = onAdd,
                    shape = RoundedCornerShape(BoostRadius.md),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                    contentPadding = PaddingValues(horizontal = BoostSpacing.sm, vertical = BoostSpacing.xs),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(addLabel, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = BoostSpacing.lg,
                end = BoostSpacing.lg,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
            content = content,
        )
    }
    }
}

@Composable
fun ConfirmDeleteDialog(what: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = BoostTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Delete this entry?", color = colors.textPrimary) },
        text = {
            Text(
                "$what will be removed. Nothing else keeps a copy of a manual entry, so " +
                    "this cannot be undone.",
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(BoostRadius.md),
                colors = ButtonDefaults.buttonColors(containerColor = colors.low),
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep", color = colors.textSecondary) } },
    )
}

/** A titled empty state, so a screen with nothing in it still says what it is for. */
@Composable
fun EmptyNote(title: String, body: String) {
    val colors = BoostTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(BoostRadius.lg))
            .padding(BoostSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BoostSpacing.xxs),
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Text(body, fontSize = 14.sp, color = colors.textSecondary)
    }
}
