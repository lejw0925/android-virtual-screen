package io.github.lejw0925.virtualscreen.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

@Composable
internal fun ArrowPreference(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    trailingIcon: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
    onClick: () -> Unit,
) {
    PreferenceItem(
        title,
        summary,
        enabled,
        modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        leading = icon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.primary) } },
    ) {
        Icon(trailingIcon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    PreferenceItem(title, summary, enabled,
        modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        leading = icon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.primary) } }) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun WindowDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    var showDialog by remember { mutableStateOf(false) }
    ArrowPreference(title, modifier, items.getOrNull(selectedIndex), enabled, icon = icon) { showDialog = true }
    if (showDialog) {
        val l = LocalLanguage.current
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title, style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    items.forEachIndexed { index, item ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = index == selectedIndex, role = Role.RadioButton,
                                onClick = { onSelectedIndexChange(index); showDialog = false })
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = index == selectedIndex, onClick = null)
                            Text(item, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text(l.text("关闭", "Close", "關閉")) } },
        )
    }
}

@Composable
internal fun DropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) = WindowDropdownPreference(title, items, selectedIndex, onSelectedIndexChange, modifier)

@Composable
internal fun PreferenceGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
internal fun StatusPill(
    text: String,
    positive: Boolean,
    modifier: Modifier = Modifier,
) {
    val container = if (positive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = if (positive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Surface(Modifier.size(7.dp), shape = androidx.compose.foundation.shape.CircleShape, color = content) {}
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun AppIconPreference(
    title: String,
    icon: Drawable?,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bitmap = remember(icon) { icon?.toBitmap(width = 96, height = 96)?.asImageBitmap() }
    PreferenceItem(title, summary, enabled, modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        leading = { if(bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp)) }) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PreferenceItem(
    title: String,
    summary: String?,
    enabled: Boolean,
    modifier: Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val alpha = if (enabled) 1f else .38f
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)) },
        supportingContent = summary?.takeIf { it.isNotBlank() }?.let { text -> {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        } },
        leadingContent = leading,
        trailingContent = trailing,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
