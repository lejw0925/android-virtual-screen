package io.github.lejw0925.virtualscreen.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.core.ActivityLog
import io.github.lejw0925.virtualscreen.core.ActivityLogEntry
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.IdentityHashMap

internal enum class LogSeverity { ALL, ERROR, WARNING }

internal fun logSeverity(entry: ActivityLogEntry): LogSeverity {
    val status = entry.status.lowercase()
    return when {
        listOf("error", "fail", "denied", "rejected", "unauthorized").any { it in status } -> LogSeverity.ERROR
        listOf("warning", "reconnect", "pending", "retry").any { it in status } -> LogSeverity.WARNING
        else -> LogSeverity.ALL
    }
}

/** Dynamic palettes fall back to the neutral foreground when needed for small text. */
internal fun readableLogColor(preferred: Color, background: Color, fallback: Color): Color {
    val a = preferred.luminance()
    val b = background.luminance()
    return if ((maxOf(a, b) + .05f) / (minOf(a, b) + .05f) >= 4.5f) preferred else fallback
}

private class LogItemKeys {
    private var next = 0L
    private val keys = IdentityHashMap<ActivityLogEntry, Long>()
    fun items(entries: List<ActivityLogEntry>): List<Pair<Long, ActivityLogEntry>> {
        val present = IdentityHashMap<ActivityLogEntry, Boolean>()
        entries.forEach { present[it] = true }
        keys.keys.retainAll(present.keys)
        return entries.map { entry -> keys.getOrPut(entry) { next++ } to entry }
    }
}

@Composable
internal fun ActivityLogs(app: VirtualScreenApplication) {
    val l = LocalLanguage.current
    Section(l.text("运行日志", "Activity log", "執行日誌")) {
        LogPanel(app.activityLog, modifier = Modifier.fillMaxWidth().height(600.dp), detailed = true)
    }
}

@Composable
internal fun HomeActivityLogs(log: ActivityLog, onOpenLogs: () -> Unit, modifier: Modifier = Modifier) {
    LogPanel(log, modifier, onOpenLogs = onOpenLogs)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LogPanel(log: ActivityLog, modifier: Modifier = Modifier, detailed: Boolean = false, onOpenLogs: (() -> Unit)? = null) {
    val l = LocalLanguage.current
    val context = LocalContext.current
    val app = context.applicationContext as VirtualScreenApplication
    val entries by log.entries.collectAsStateWithLifecycle()
    val writeError by log.writeError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var paused by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf(emptyList<ActivityLogEntry>()) }
    var severity by remember { mutableStateOf(LogSeverity.ALL) }
    var category by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val source = if (paused) frozen else entries
    val categories = listOf(null, "runtime", "mcp", "tunnel")
    val visible = remember(source, severity, category) { source.filter {
        (severity == LogSeverity.ALL || logSeverity(it) == severity) &&
            (categories[category] == null || it.category == categories[category])
    } }
    val keys = remember(log) { LogItemKeys() }
    val logItems = remember(visible) { keys.items(visible).asReversed() }
    val listState = rememberLazyListState()
    LaunchedEffect(severity, category) { listState.scrollToItem(0) }
    val time = remember(detailed) { DateTimeFormatter.ofPattern(if (detailed) "MM-dd HH:mm:ss.SSS" else "HH:mm:ss.SSS").withZone(ZoneId.systemDefault()) }
    val placement = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val entrySurface = MaterialTheme.colorScheme.surfaceContainerHigh
    val title = l.text("运行日志", "Activity log", "執行日誌")

    Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = surface) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (onOpenLogs != null) TextButton(onClick = onOpenLogs) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                    } else Text(title, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleSmall)
                }
                IconButton(onClick = { if (!paused) frozen = entries; paused = !paused }, modifier = Modifier.size(48.dp)) {
                    Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        if (paused) l.text("继续日志", "Resume logs", "繼續日誌") else l.text("暂停日志", "Pause logs", "暫停日誌"))
                }
                IconButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty(), modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.DeleteOutline, l.text("清空日志", "Clear logs", "清空日誌"))
                }
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, app.diagnosticReport(visible))
                    context.startActivity(Intent.createChooser(send, l.text("分享日志", "Share logs", "分享日誌")))
                }, enabled = visible.isNotEmpty(), modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Share, l.text("分享日志", "Share logs", "分享日誌"))
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogSeverity.entries.forEach { value ->
                    FilterChip(selected = severity == value, onClick = { severity = value },
                        label = { Text(when (value) {
                            LogSeverity.ALL -> l.text("全部", "All", "全部")
                            LogSeverity.ERROR -> l.text("错误", "Errors", "錯誤")
                            LogSeverity.WARNING -> l.text("警告", "Warnings", "警告")
                        }) },
                        leadingIcon = when (value) {
                            LogSeverity.ERROR -> { { Icon(Icons.Default.ErrorOutline, null, Modifier.size(FilterChipDefaults.IconSize)) } }
                            LogSeverity.WARNING -> { { Icon(Icons.Default.WarningAmber, null, Modifier.size(FilterChipDefaults.IconSize)) } }
                            LogSeverity.ALL -> null
                        },
                    )
                }
            }
            if (detailed) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(l.text("所有来源", "All sources", "所有來源"), l.text("软件", "App", "軟體"), "MCP", l.text("隧道", "Tunnels", "通道")).forEachIndexed { index, label ->
                    FilterChip(selected = category == index, onClick = { category = index }, label = { Text(label) })
                }
            }
            if (paused) Text(l.text("已暂停显示，新日志仍在记录。", "Display paused; new entries are still recorded.", "已暫停顯示，新日誌仍在記錄。"),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (writeError) Text(l.text("日志无法保存，仍可查看本次记录。", "Log storage is unavailable; session entries remain visible.", "日誌無法儲存，仍可檢視本次記錄。"),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            if (logItems.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.DeleteOutline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(l.text("暂无日志", "No logs", "暫無日誌"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(
                // Filtering replaces the list; starting from the top avoids items animating
                // through the header from the previous scroll offset.
                Modifier.fillMaxWidth().weight(1f).clipToBounds(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(logItems, key = { it.first }, contentType = { "log" }) { (_, entry) ->
                    LogEntryCard(entry, time, entrySurface,
                        Modifier.animateItem(fadeInSpec = effects, placementSpec = placement, fadeOutSpec = effects))
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(l.text("清空日志？", "Clear logs?", "清空日誌？")) },
        text = { Text(l.text("删除此设备保存的运行记录。", "Delete the activity history saved on this device.", "刪除此裝置儲存的執行記錄。")) },
        confirmButton = { TextButton(onClick = { scope.launch { log.clear(); frozen = emptyList() }; confirmClear = false }) { Text(l.text("清空", "Clear", "清空")) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(l.text("取消", "Cancel", "取消")) } })
}

/**
 * One entry as a tonal card. Severity is carried by a large, low-opacity watermark in the
 * bottom-right corner instead of a colored rail, and the enumerated fields sit in read-only chips.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
private fun statusIcon(level: LogSeverity) = when (level) {
    LogSeverity.ERROR -> Icons.Default.ErrorOutline
    LogSeverity.WARNING -> Icons.Default.WarningAmber
    LogSeverity.ALL -> Icons.Default.CheckCircle
}

/**
 * Status text is free-form ("started", "changed", "denied"), so the glyph follows the words
 * rather than only the severity, which keeps a neutral status from claiming a success check.
 */
private fun statusTextIcon(status: String, level: LogSeverity): ImageVector {
    val value = status.lowercase()
    return when {
        level == LogSeverity.ERROR -> Icons.Default.ErrorOutline
        level == LogSeverity.WARNING -> Icons.Default.WarningAmber
        listOf("started", "start", "created", "completed", "ready", "connected", "resumed").any { it in value } ->
            Icons.Default.CheckCircle
        listOf("changed", "updated", "configured").any { it in value } -> Icons.Default.Sync
        listOf("stopped", "closed", "released", "paused", "ended").any { it in value } -> Icons.Default.StopCircle
        else -> Icons.Default.Circle
    }
}

/** The chip glyph names the log source rather than repeating a generic status mark. */
private fun categoryIcon(category: String) = when (category.lowercase()) {
    "mcp" -> Icons.Default.Hub
    "tunnel" -> Icons.Default.SettingsEthernet
    "runtime" -> Icons.Default.Memory
    else -> Icons.Default.Description
}

@Composable
private fun LogEntryCard(entry: ActivityLogEntry, time: DateTimeFormatter, surface: Color, modifier: Modifier = Modifier) {
    val l = LocalLanguage.current
    val colors = MaterialTheme.colorScheme
    val status = LocalStatusColors.current
    val level = logSeverity(entry)
    val accent = when (level) {
        LogSeverity.ERROR -> colors.error
        LogSeverity.WARNING -> status.warning
        LogSeverity.ALL -> colors.primary
    }
    val tone = when (level) {
        LogSeverity.ERROR -> StatusTone.Negative
        LogSeverity.WARNING -> StatusTone.Negative
        LogSeverity.ALL -> StatusTone.Neutral
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = surface) {
        Box(Modifier.fillMaxWidth()) {
            Icon(
                statusIcon(level),
                null,
                Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 4.dp).size(72.dp),
                tint = accent.copy(alpha = .16f),
            )
            SelectionContainer {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(entry.operation, style = MaterialTheme.typography.titleSmallEmphasized,
                        color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(time.format(Instant.ofEpochMilli(entry.time)),
                        style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariant)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StatusChip(entry.category.uppercase(), StatusTone.Neutral, icon = categoryIcon(entry.category))
                        StatusChip(entry.status, tone, icon = statusTextIcon(entry.status, level))
                        entry.client?.let { StatusChip(it, StatusTone.Neutral, icon = Icons.Default.Devices) }
                    }
                    listOfNotNull(
                        entry.peerIp?.let { "IP" to it },
                        entry.forwardedIp?.let { l.text("代理报告 IP", "proxy-reported IP", "代理回報 IP") to it },
                    ).forEach { (label, value) ->
                        Text("$label: $value", style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    entry.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
