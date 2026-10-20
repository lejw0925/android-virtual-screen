package io.github.lejw0925.virtualscreen.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.mcp.McpServerState
import io.github.lejw0925.virtualscreen.tunnel.*
import androidx.compose.material3.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

@Composable internal fun Action(label: String,enabled: Boolean = true,onClick: () -> Unit) {
    TextButton(onClick=onClick,enabled=enabled) {
        Text(label)
    }
}
@Composable internal fun Field(label: String,value: String,secret: Boolean = false,multiline: Boolean = false,enabled: Boolean = true,onValue: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    LaunchedEffect(value) { if(field.text != value) field=TextFieldValue(value) }
    OutlinedTextField(
        value=field,
        onValueChange={ field=it; onValue(it.text.take(if(multiline) 65536 else 20000)) },
        label={ Text(label) },
        enabled=enabled,
        singleLine=!multiline,
        maxLines=if(multiline) 6 else 1,
        visualTransformation=if(secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier=Modifier.fillMaxWidth()
    )
}
internal fun copy(context: Context,text: String) {
    val clip=ClipData.newPlainText(context.getString(io.github.lejw0925.virtualscreen.R.string.app_name),text)
    clip.description.extras=android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE,true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    android.widget.Toast.makeText(context,context.getString(io.github.lejw0925.virtualscreen.R.string.clipboard_copied),android.widget.Toast.LENGTH_SHORT).show()
}
internal fun connectionAddress(server: McpServerState, tunnel: TunnelState): String =
    tunnel.publicAddress?.replaceFirst("tcp://", "http://")
        ?: (if (server.host == "0.0.0.0") lanAddresses(server.port).firstOrNull() else null)
        ?: "http://127.0.0.1:${server.port}/mcp"

internal fun connectionAddress(app: VirtualScreenApplication): String = connectionAddress(app.mcp.state(), app.tunnels.state())
internal fun lanAddresses(port: Int): List<String> = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
        .filterIsInstance<java.net.Inet4Address>().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }.map { "http://${it.hostAddress}:$port/mcp" }.distinct()
}.getOrDefault(emptyList())

internal fun tunnelLabel(l: UiLanguage,mode: TunnelMode)=when(mode) {
    TunnelMode.OFF -> l.text("关闭","Off","關閉")
    TunnelMode.FRPC -> "frpc"
    TunnelMode.CLOUDFLARE_QUICK -> l.text("随机地址","Quick Tunnel","隨機位址")
    TunnelMode.CLOUDFLARE_NAMED -> l.text("固定域名","Named tunnel","固定網域")
}
internal fun phaseLabel(l: UiLanguage,phase: String)=when(phase) {
    "connected" -> l.text("已连接","Connected","已連線")
    "connecting", "starting" -> l.text("正在连接","Connecting","正在連線")
    "verifying" -> l.text("正在验证公网连接","Verifying public connection","正在驗證公網連線")
    "reconnecting" -> l.text("重新连接中","Reconnecting","重新連線中")
    "login", "logging_in" -> l.text("等待官方授权","Awaiting authorization","等待官方授權")
    "credentials_ready", "certificate_ready" -> l.text("凭据已就绪","Credentials ready","憑證已就緒")
    "tunnel_configuring" -> l.text("正在配置隧道","Configuring tunnel","正在設定通道")
    "dns_binding" -> l.text("正在绑定域名","Binding hostname","正在繫結網域")
    "dns_ready" -> l.text("域名已绑定","Hostname ready","網域已繫結")
    "error", "failed" -> l.text("连接失败","Connection failed","連線失敗")
    else -> l.text("未连接","Disconnected","未連線")
}
