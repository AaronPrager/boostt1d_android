package com.boostt1d.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
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

/**
 * The fifty states plus DC, in the order iOS lists them. Asked only of users in the United
 * States, and only ever used as demographics on the registration payload.
 */
object UsStates {
    val all = listOf(
        "Alabama", "Alaska", "Arizona", "Arkansas", "California", "Colorado", "Connecticut",
        "Delaware", "Florida", "Georgia", "Hawaii", "Idaho", "Illinois", "Indiana", "Iowa",
        "Kansas", "Kentucky", "Louisiana", "Maine", "Maryland", "Massachusetts", "Michigan",
        "Minnesota", "Mississippi", "Missouri", "Montana", "Nebraska", "Nevada",
        "New Hampshire", "New Jersey", "New Mexico", "New York", "North Carolina",
        "North Dakota", "Ohio", "Oklahoma", "Oregon", "Pennsylvania", "Rhode Island",
        "South Carolina", "South Dakota", "Tennessee", "Texas", "Utah", "Vermont", "Virginia",
        "Washington", "West Virginia", "Wisconsin", "Wyoming", "District of Columbia",
    )

    /** The country code the state question belongs to. */
    const val COUNTRY_CODE = "US"
}

/** Full-screen state list with a search field, matching the iOS StatePickerView. */
@Composable
fun StatePickerDialog(
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val colors = BoostTheme.colors
    var query by remember { mutableStateOf("") }
    val results = remember(query) {
        if (query.isBlank()) UsStates.all
        else UsStates.all.filter { it.contains(query.trim(), ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(BoostSpacing.md),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Select state", fontSize = 20.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel", color = colors.primary) }
                }

                Box(modifier = Modifier.padding(vertical = BoostSpacing.xs)) {
                    BoostTextField(value = query, onValueChange = { query = it }, placeholder = "Search")
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(results, key = { it }) { state ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(state) }
                                .padding(horizontal = BoostSpacing.xs, vertical = BoostSpacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(state, fontSize = 16.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
                            if (state == selected) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.primary)
                            }
                        }
                        BoostDivider()
                    }
                }
            }
        }
    }
}
