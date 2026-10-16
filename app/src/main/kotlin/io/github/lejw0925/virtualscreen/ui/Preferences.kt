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
    onClick: () -> Unit,
) {
    PreferenceItem(title, summary, enabled, modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
) {
    PreferenceItem(title, summary, enabled,
        modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)) {
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
) {
    var showDialog by remember { mutableStateOf(false) }
    ArrowPreference(title, modifier, items.getOrNull(selectedIndex), enabled) { showDialog = true }
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
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
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
