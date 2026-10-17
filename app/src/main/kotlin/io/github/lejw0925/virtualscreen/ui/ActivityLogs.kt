package io.github.lejw0925.virtualscreen.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ActivityLogs(app: VirtualScreenApplication) {
    val l = LocalLanguage.current
    val entries by app.activityLog.entries.collectAsState()
    val writeError by app.activityLog.writeError.collectAsState()
    val scope = rememberCoroutineScope()
    var category by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val visible = remember(entries, category) { entries.filter { category == 0 || it.category == if (category == 1) "mcp" else "tunnel" } }
    val time = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault()) }

    Section(l.text("连接与操作记录", "Connections and operations", "連線與操作記錄")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(l.text("全部", "All", "全部"), "MCP", l.text("隧道", "Tunnels", "通道")).forEachIndexed { index, label ->
                FilterChip(
                    selected = category == index,
                    onClick = { category = index },
                    label = { Text(label) },
                )
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (writeError) Text(l.text("日志文件暂时无法写入，仍可查看本次运行记录。", "Log storage is unavailable; current session entries remain visible.",
                "日誌檔案暫時無法寫入，仍可檢視本次執行記錄。"), color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { copy(app, visible.joinToString("\n") { it.text() }) }, enabled = visible.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentCopy,null)
                    Spacer(Modifier.width(8.dp))
                    Text(l.text("复制日志", "Copy logs", "複製日誌"))
                }
                OutlinedButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.DeleteForever,null)
                    Spacer(Modifier.width(8.dp))
                    Text(l.text("清空", "Clear", "清空"))
                }
            }
            if (visible.isEmpty()) Text(l.text("暂无记录", "No entries yet", "暫無記錄"), style = MaterialTheme.typography.bodyMedium)
            else LazyColumn(Modifier.fillMaxWidth().height(440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(visible.asReversed()) { entry ->
                    SelectionContainer {
                        Surface(
                            Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(time.format(Instant.ofEpochMilli(entry.time)), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${entry.operation} · ${entry.status}", style = MaterialTheme.typography.bodyMedium)
                                listOfNotNull(entry.client, entry.peerIp).takeIf { it.isNotEmpty() }?.let {
                                    Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                                entry.forwardedIp?.let {
                                    Text(l.text("代理报告 IP：", "Proxy-reported IP: ", "代理回報 IP：") + it, style = MaterialTheme.typography.bodySmall)
                                }
                                entry.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(l.text("清空日志？", "Clear logs?", "清空日誌？")) },
        text = { Text(l.text("删除此设备保存的运行记录。", "Delete the activity history saved on this device.", "刪除此裝置儲存的執行記錄。")) },
        confirmButton = { TextButton(onClick = { scope.launch { app.activityLog.clear() }; confirmClear = false }) { Icon(Icons.Default.DeleteForever,null); Spacer(Modifier.width(8.dp)); Text(l.text("清空", "Clear", "清空")) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(l.text("取消", "Cancel", "取消")) } })
}
