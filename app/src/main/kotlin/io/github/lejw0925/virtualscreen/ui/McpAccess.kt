package io.github.lejw0925.virtualscreen.ui

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.backend.BackendKind
import io.github.lejw0925.virtualscreen.core.runtime


private enum class McpPermission { BACKEND, NOTIFICATIONS, PREVIEW }

/** Background operation is recommended but optional: never blocks MCP or the first-run check. */
private fun backgroundRestricted(app: VirtualScreenApplication): Boolean =
    !app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)

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
}

/** All UI entry points use the same permission check and application-owned startup. */
@Composable internal fun rememberMcpToggle(app: VirtualScreenApplication,notifications: () -> Unit): (Boolean) -> Unit {
    val l = LocalLanguage.current
    var missing by remember { mutableStateOf(emptyList<McpPermission>()) }
    var showBackgroundNotice by rememberSaveable { mutableStateOf(false) }
    fun enable() {
        missing = missingMcpPermissions(app)
        if (missing.isNotEmpty()) app.activityLog.runtime("mcp_permissions", "warning", "missing=${missing.joinToString { it.name }}")
        if(missing.isEmpty()) {
            app.setMcpEnabled(true)
            // Optional tip, shown after the server starts so it never gates the first run.
            if (backgroundRestricted(app) && !app.settings.mcpBackgroundNoticeAcknowledged) showBackgroundNotice = true
        }
    }
    if (showBackgroundNotice) McpBackgroundNotice(
        onDismiss = {
            app.settings.acknowledgeMcpBackgroundNotice()
            showBackgroundNotice = false
        },
        onOpenSettings = {
            app.settings.acknowledgeMcpBackgroundNotice()
            showBackgroundNotice = false
            openBackgroundSettings(app)
        },
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
            },onClick={
                val intent = when(permission) {
                    McpPermission.BACKEND -> Intent(app,SettingsActivity::class.java).putExtra("page","BACKEND")
                    McpPermission.NOTIFICATIONS -> { notifications(); null }
                    McpPermission.PREVIEW -> Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS",Uri.parse("package:${app.packageName}"))
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
        } else {
            enable()
        }
    }
}

internal fun openBackgroundSettings(app: VirtualScreenApplication) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}"))
    val target = if (intent.resolveActivity(app.packageManager) != null) intent
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
    app.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun McpBackgroundNotice(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val l = LocalLanguage.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.BatterySaver, null) },
        title = { Text(l.text("后台运行（可选）", "Background operation (optional)", "背景執行（選用）")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(l.text(
                    "允许后台运行可以减少 MCP 和隧道被系统中断，但它是可选项：不授权也能正常完成首次检查和连接。之后可在「设置 → 后台运行」中调整。",
                    "Allowing background operation reduces the chance of MCP and tunnels being stopped in the background. It is optional: the first-run check and connections work without it. You can change this later in Settings → Background operation.",
                    "允許背景執行可減少 MCP 和通道被系統中斷，但它是選用項：不授權也能正常完成首次檢查和連線。之後可在「設定 → 背景執行」中調整。",
                ))
                TextButton(onClick = onOpenSettings) {
                    Text(l.text("后台运行设置", "Background settings", "背景執行設定"))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(l.text("知道了", "Got it", "知道了")) }
        },
    )
}
