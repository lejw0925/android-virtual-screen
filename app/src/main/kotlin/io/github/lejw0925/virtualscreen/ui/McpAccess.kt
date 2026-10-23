package io.github.lejw0925.virtualscreen.ui

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.backend.BackendKind
import io.github.lejw0925.virtualscreen.core.runtime


private enum class McpPermission { BACKEND, NOTIFICATIONS, PREVIEW, BACKGROUND }

private fun missingMcpPermissions(app: VirtualScreenApplication): List<McpPermission> = buildList {
    val authorized = when(app.settings.flow.value.backend) {
        BackendKind.ROOT -> app.getSharedPreferences("settings",0).getBoolean("rootGranted",false)
        BackendKind.SHIZUKU -> io.github.lejw0925.virtualscreen.backend.ShizukuBackend.isPrivilegeReady(app)
    }
    if(!authorized) add(McpPermission.BACKEND)
    if(!app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) add(McpPermission.NOTIFICATIONS)
    val pip = app.getSystemService(AppOpsManager::class.java).checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
        android.os.Process.myUid(),app.packageName)
    if(app.settings.flow.value.pipEnabled && pip != AppOpsManager.MODE_ALLOWED && pip != AppOpsManager.MODE_DEFAULT) add(McpPermission.PREVIEW)
    if(!app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)) add(McpPermission.BACKGROUND)
}

/** All UI entry points use the same permission check and application-owned startup. */
@Composable internal fun rememberMcpToggle(app: VirtualScreenApplication,notifications: () -> Unit): (Boolean) -> Unit {
    val l = LocalLanguage.current
    var missing by remember { mutableStateOf(emptyList<McpPermission>()) }
    var showBackgroundNotice by rememberSaveable { mutableStateOf(false) }
    fun enable() {
        missing = missingMcpPermissions(app)
        if (missing.isNotEmpty()) app.activityLog.runtime("mcp_permissions", "warning", "missing=${missing.joinToString { it.name }}")
        if(missing.isEmpty()) app.setMcpEnabled(true)
    }
    if (showBackgroundNotice) McpBackgroundNotice(
        onConfirm = {
            app.settings.acknowledgeMcpBackgroundNotice()
            showBackgroundNotice = false
            enable()
        },
        onDismiss = { showBackgroundNotice = false },
        onOpenSettings = { openBackgroundSettings(app) },
    )
    if(missing.isNotEmpty()) AppDialog(onDismissRequest={ missing=emptyList() }) {
        Text(
            text = l.text("开启 MCP 前需要授权","Permissions needed for MCP","開啟 MCP 前需要授權"),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Column(Modifier.padding(vertical=8.dp)) {
            missing.forEach { permission -> ArrowPreference(title=when(permission) {
                McpPermission.BACKEND -> l.text("授权 Root / Shizuku / Stellar","Authorize Root / Shizuku / Stellar","授權 Root / Shizuku / Stellar")
                McpPermission.NOTIFICATIONS -> l.text("允许通知","Allow notifications","允許通知")
                McpPermission.PREVIEW -> l.text("允许画中画","Allow picture-in-picture","允許子母畫面")
                McpPermission.BACKGROUND -> l.text("允许后台运行","Allow background operation","允許背景執行")
            },onClick={
                val intent = when(permission) {
                    McpPermission.BACKEND -> Intent(app,SettingsActivity::class.java).putExtra("page","BACKEND")
                    McpPermission.NOTIFICATIONS -> { notifications(); null }
                    McpPermission.PREVIEW -> Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS",Uri.parse("package:${app.packageName}"))
                    McpPermission.BACKGROUND -> { openBackgroundSettings(app); null }
                }
                intent?.let {
                    val target=if(it.resolveActivity(app.packageManager)!=null) it else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${app.packageName}"))
                    app.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) }
        }
        Button(
            onClick={ enable() },
            modifier=Modifier.fillMaxWidth(),
        ) {
            Text(l.text("重新检查并开启","Check again and enable","重新檢查並開啟"))
        }
        TextButton(
            onClick={ missing=emptyList() },
            modifier=Modifier.fillMaxWidth(),
        ) {
            Text(l.text("稍后","Later","稍後"))
        }
    }
    return { enabled ->
        if (!enabled) {
            showBackgroundNotice = false
            missing = emptyList()
            app.setMcpEnabled(false)
        } else if (!app.settings.mcpBackgroundNoticeAcknowledged) {
            showBackgroundNotice = true
        } else {
            enable()
        }
    }
}

private fun openBackgroundSettings(app: VirtualScreenApplication) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}"))
    val target = if (intent.resolveActivity(app.packageManager) != null) intent
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
    app.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun McpBackgroundNotice(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val l = LocalLanguage.current
    var secondsRemaining by rememberSaveable { mutableIntStateOf(3) }
    LaunchedEffect(Unit) {
        while (secondsRemaining > 0) {
            delay(1000)
            secondsRemaining--
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.BatterySaver, null) },
        title = { Text(l.text("允许后台运行", "Allow background operation", "允許背景執行")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(l.text(
                    "请在系统设置中允许本应用后台运行，将电池策略设为「不限制」，并开启自启动（如有）。否则 MCP 和隧道服务可能无法启动，或在后台中断。",
                    "Allow this app to run in the background in system settings, set battery usage to Unrestricted, and enable autostart if available. Otherwise, MCP and tunnel services may fail to start or may be stopped in the background.",
                    "請在系統設定中允許本應用程式背景執行，將電池策略設為「不限制」，並開啟自動啟動（如有）。否則 MCP 和通道服務可能無法啟動，或在背景中斷。",
                ))
                TextButton(onClick = onOpenSettings) {
                    Text(l.text("后台运行设置", "Background settings", "背景執行設定"))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = secondsRemaining == 0) {
                Text(if (secondsRemaining > 0) l.text(
                    "确定（${secondsRemaining}秒）", "OK (${secondsRemaining}s)", "確定（${secondsRemaining}秒）",
                ) else l.text("确定", "OK", "確定"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(l.text("取消", "Cancel", "取消")) }
        },
    )
}
