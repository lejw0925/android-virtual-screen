package io.github.lejw0925.virtualscreen.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.mcp.McpNetwork
import io.github.lejw0925.virtualscreen.mcp.McpServerState
import io.github.lejw0925.virtualscreen.mcp.isTailscaleAddress
import io.github.lejw0925.virtualscreen.mcp.isTailscaleInterface
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
internal fun connectionAddress(
    server: McpServerState,
    tunnel: TunnelState,
    addresses: (Int, McpNetwork) -> List<String> = ::lanAddresses,
): String? {
    if (server.network == McpNetwork.TAILSCALE) return addresses(server.port, server.network).firstOrNull()
    return tunnel.publicAddress?.replaceFirst("tcp://", "http://")
        ?: (if (server.network == McpNetwork.LAN) addresses(server.port, server.network).firstOrNull() else null)
        ?: "http://127.0.0.1:${server.port}/mcp"
}

internal fun tailscaleAddressUnavailable(language: UiLanguage) = language.text(
    "没有可用的 Tailscale 地址。", "No Tailscale address available.", "沒有可用的 Tailscale 位址。",
)

internal fun copyMcpConnection(
    app: VirtualScreenApplication,
    language: UiLanguage,
    addressOnly: Boolean = false,
    selectedAddress: String? = null,
) {
    val server = app.mcp.state()
    val network = if (server.running) server.network else app.settings.flow.value.mcpNetwork
    val endpoint = if (selectedAddress == null) {
        connectionAddress(server.copy(network = network), app.tunnels.state())
    } else {
        lanAddresses(server.port, network).firstOrNull { it == selectedAddress }
    }
    if (endpoint == null) {
        val message = if (network == McpNetwork.TAILSCALE) tailscaleAddressUnavailable(language) else
            language.text("地址已不可用。", "Address no longer available.", "位址已不可用。")
        Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
        return
    }
    copy(app, if (addressOnly) endpoint else app.mcp.clientConfiguration(endpoint))
}

@Composable internal fun rememberMcpAddresses(server: McpServerState): List<String> {
    var addresses by remember(server.network, server.port, server.running) {
        mutableStateOf(lanAddresses(server.port, server.network))
    }
    LifecycleResumeEffect(server.network, server.port) {
        addresses = lanAddresses(server.port, server.network)
        onPauseOrDispose { }
    }
    return addresses
}

internal fun lanAddresses(port: Int, network: McpNetwork): List<String> = runCatching {
    if (network == McpNetwork.LOCAL) return emptyList()
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && (network != McpNetwork.TAILSCALE || isTailscaleInterface(it.name)) }
        .flatMap { it.inetAddresses.toList() }
        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress &&
            if (network == McpNetwork.TAILSCALE) isTailscaleAddress(it) else it is java.net.Inet4Address }
        .sortedBy { it !is java.net.Inet4Address }
        .map { address ->
            val host = address.hostAddress!!.substringBefore('%')
            if (address is java.net.Inet6Address) "http://[$host]:$port/mcp" else "http://$host:$port/mcp"
        }.distinct()
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
