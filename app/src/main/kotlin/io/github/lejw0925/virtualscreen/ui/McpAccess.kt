package io.github.lejw0925.virtualscreen.ui

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.backend.BackendKind
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class McpPermission { BACKEND, NOTIFICATIONS, PREVIEW, BACKGROUND }

private fun missingMcpPermissions(app: VirtualScreenApplication): List<McpPermission> = buildList {
    val authorized = when(app.settings.flow.value.backend) {
        BackendKind.ROOT -> app.getSharedPreferences("settings",0).getBoolean("rootGranted",false)
        BackendKind.SHIZUKU -> runCatching { rikka.shizuku.Shizuku.pingBinder() &&
            rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    }
    if(!authorized) add(McpPermission.BACKEND)
    if(!app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) add(McpPermission.NOTIFICATIONS)
    val pip = app.getSystemService(AppOpsManager::class.java).checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
        android.os.Process.myUid(),app.packageName)
    if(pip != AppOpsManager.MODE_ALLOWED && pip != AppOpsManager.MODE_DEFAULT) add(McpPermission.PREVIEW)
    if(!app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)) add(McpPermission.BACKGROUND)
}

/** All UI entry points use the same permission check and application-owned startup. */
@Composable internal fun rememberMcpToggle(app: VirtualScreenApplication,notifications: () -> Unit): (Boolean) -> Unit {
    val l = LocalLanguage.current
    var missing by remember { mutableStateOf(emptyList<McpPermission>()) }
    fun enable() {
        missing = missingMcpPermissions(app)
        if(missing.isEmpty()) app.setMcpEnabled(true)
    }
    if(missing.isNotEmpty()) Dialog(onDismissRequest={ missing=emptyList() }) { Card {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(l.text("开启 MCP 前需要授权","Permissions needed for MCP","開啟 MCP 前需要授權"),style=MiuixTheme.textStyles.title3)
            Text(l.text("授权后返回，重新检查即可开启。Root 或 Shizuku 授权后会自动启用节点服务。","Return after granting access and check again. Root or Shizuku automatically enables the node service.","授權後返回，重新檢查即可開啟。Root 或 Shizuku 授權後會自動啟用節點服務。"),style=MiuixTheme.textStyles.body2)
            missing.forEach { permission -> ArrowPreference(title=when(permission) {
                McpPermission.BACKEND -> l.text("授权 Root 或 Shizuku","Authorize Root or Shizuku","授權 Root 或 Shizuku")
                McpPermission.NOTIFICATIONS -> l.text("允许通知","Allow notifications","允許通知")
                McpPermission.PREVIEW -> l.text("允许画中画","Allow picture-in-picture","允許子母畫面")
                McpPermission.BACKGROUND -> l.text("允许后台运行","Allow background operation","允許背景執行")
            },onClick={
                val intent = when(permission) {
                    McpPermission.BACKEND -> Intent(app,SettingsActivity::class.java).putExtra("page","BACKEND")
                    McpPermission.NOTIFICATIONS -> { notifications(); null }
                    McpPermission.PREVIEW -> Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS",Uri.parse("package:${app.packageName}"))
                    McpPermission.BACKGROUND -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:${app.packageName}"))
                }
                intent?.let {
                    val target=if(it.resolveActivity(app.packageManager)!=null) it else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${app.packageName}"))
                    app.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) }
            Button(onClick={ enable() },modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColorsPrimary()) {
                Text(l.text("重新检查并开启","Check again and enable","重新檢查並開啟"))
            }
            Action(l.text("稍后","Later","稍後")) { missing=emptyList() }
        }
    } }
    return { enabled -> if(enabled) enable() else app.setMcpEnabled(false) }
}
