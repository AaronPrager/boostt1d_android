package com.boostt1d.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions

/** Label above a field, so every step asks in the same voice. */
@Composable
fun BoostFieldLabel(
    text: String,
    modifier: Modifier = Modifier,
    isOptional: Boolean = false,
    onInfo: (() -> Unit)? = null,
) {
    val colors = BoostTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)

        if (isOptional) {
            Text(
                "Optional",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textTertiary,
                modifier = Modifier
                    .background(colors.surfaceMuted, CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        if (onInfo != null) {
            Icon(
                Icons.Filled.Info,
                contentDescription = "More information",
                tint = colors.primary,
                modifier = Modifier.size(16.dp).clickable(onClick = onInfo),
            )
        }
    }
}

@Composable
fun BoostTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    /**
     * Masks the value and offers a reveal toggle. For anything that grants access to
     * someone's data: a field rendering a token in clear text puts it in every
     * screenshot, screen recording and accessibility dump of that screen.
     */
    isSecret: Boolean = false,
) {
    val colors = BoostTheme.colors
    var revealed by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = colors.textTertiary) },
        singleLine = singleLine,
        visualTransformation = if (isSecret && !revealed) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        trailingIcon = if (!isSecret) null else {
            {
                Icon(
                    imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (revealed) "Hide token" else "Show token",
                    tint = colors.textTertiary,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { revealed = !revealed },
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(BoostRadius.md),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceMuted,
            unfocusedContainerColor = colors.surfaceMuted,
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.border,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            cursorColor = colors.primary,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * A menu-style picker.
 *
 * Built from a Box plus DropdownMenu rather than ExposedDropdownMenuBox: the
 * exposed variant is still experimental and its anchor API has changed between
 * Material 3 releases, which is not a dependency worth taking for a list of ages.
 */
@Composable
fun <T> BoostDropdownField(
    selected: T?,
    placeholder: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        val currentLabel = selected?.let(optionLabel) ?: placeholder
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceMuted, RoundedCornerShape(BoostRadius.md))
                .border(1.dp, colors.border, RoundedCornerShape(BoostRadius.md))
                .clickable(role = Role.DropdownList) { expanded = true }
                .semantics(mergeDescendants = true) { contentDescription = currentLabel }
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = currentLabel,
                color = if (selected == null) colors.textTertiary else colors.textPrimary,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = colors.textTertiary)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(colors.surface),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option), color = colors.textPrimary) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Two or more mutually exclusive choices, shown side by side. */
@Composable
fun <T> BoostSegmented(
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BoostTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceMuted, RoundedCornerShape(BoostRadius.md))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (isSelected) colors.surface else Color.Transparent,
                        RoundedCornerShape(BoostRadius.sm),
                    )
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    )
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    optionLabel(option),
                    color = if (isSelected) colors.textPrimary else colors.textSecondary,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/** A checkbox row for the things a person has to actively agree to. */
@Composable
fun BoostConsentRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    readLabel: String? = null,
    onRead: (() -> Unit)? = null,
) {
    val colors = BoostTheme.colors
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    onValueChange = onCheckedChange,
                    role = Role.Checkbox,
                )
                .semantics(mergeDescendants = true) { contentDescription = text },
            horizontalArrangement = Arrangement.spacedBy(BoostSpacing.sm),
        ) {
            Icon(
                imageVector = if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (checked) colors.inRange else colors.textTertiary,
                modifier = Modifier.size(22.dp),
            )
            Text(text, color = colors.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        }

        if (readLabel != null && onRead != null) {
            Text(
                readLabel,
                color = colors.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 34.dp).clickable(onClick = onRead),
            )
        }
    }
}

/** A soft-tinted round icon, matching the iOS BoostSoftIcon. */
@Composable
fun BoostSoftIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(tint.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun BoostDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(BoostTheme.colors.border),
    )
}

@Composable
fun BoostCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = BoostTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(BoostRadius.lg))
            .border(1.dp, colors.border, RoundedCornerShape(BoostRadius.lg))
            .padding(BoostSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BoostSpacing.xs),
        content = content,
    )
}
