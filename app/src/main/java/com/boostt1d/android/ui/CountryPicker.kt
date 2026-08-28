package com.boostt1d.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.boostt1d.android.data.Countries

/** Full-screen country list with a search field, matching the iOS picker sheet. */
@Composable
fun CountryPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (Countries.Country) -> Unit,
) {
    val colors = BoostTheme.colors
    var query by remember { mutableStateOf("") }
    val results = remember(query) {
        if (query.isBlank()) Countries.all
        else Countries.all.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(BoostSpacing.md),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Select country",
                        fontSize = 20.sp,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("Cancel", color = colors.primary) }
                }

                Box(modifier = Modifier.padding(vertical = BoostSpacing.xs)) {
                    BoostTextField(value = query, onValueChange = { query = it }, placeholder = "Search")
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(results, key = { it.code }) { country ->
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(country) },
                        ) {
                            Text(
                                country.name,
                                fontSize = 16.sp,
                                color = colors.textPrimary,
                                modifier = Modifier.padding(
                                    horizontal = BoostSpacing.xs,
                                    vertical = BoostSpacing.md,
                                ),
                            )
                            BoostDivider()
                        }
                    }
                }
            }
        }
    }
}
