package io.github.lejw0925.virtualscreen.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import io.github.lejw0925.virtualscreen.tunnel.*
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.*

import androidx.compose.ui.text.input.TextFieldValue
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
@Composable internal fun TunnelSettings(app: VirtualScreenApplication,task: (suspend () -> Unit) -> Unit,notifications: () -> Unit) {
    val l=LocalLanguage.current
    val server by app.mcp.flow.collectAsState()
    val tunnel by app.tunnels.flow.collectAsState()
    val settings by app.settings.flow.collectAsState()
    var mode by remember { mutableStateOf(tunnel.mode) }
    val loaded=remember { app.tunnels.loadProfile() }
    var host by remember { mutableStateOf(loaded.first?.serverAddress ?: "") }
    var port by remember { mutableStateOf((loaded.first?.serverPort ?: 7000).toString()) }
    var frpToken by remember { mutableStateOf(loaded.first?.token ?: "") }
    var protocol by remember { mutableStateOf(loaded.first?.protocol ?: "tcp") }
    var remotePort by remember { mutableStateOf((loaded.first?.remotePort ?: 8732).toString()) }
    var frpDomain by remember { mutableStateOf(loaded.first?.hostname ?: "") }
    var toml by remember { mutableStateOf(loaded.first?.advancedToml ?: "") }
    var advanced by remember { mutableStateOf(false) }
    var certificate by remember { mutableStateOf(loaded.first?.certificatePem ?: "") }
    var privateKey by remember { mutableStateOf(loaded.first?.privateKeyPem ?: "") }
    var publicPort by remember { mutableStateOf(loaded.first?.publicPort?.toString() ?: "") }
    var cfToken by remember { mutableStateOf(loaded.second?.tunnelToken ?: "") }
    var cfHost by remember { mutableStateOf(loaded.second?.hostname ?: "") }
    var tunnelId by remember { mutableStateOf(loaded.second?.tunnelId ?: "") }
    var tunnelName by remember { mutableStateOf("android-virtual-screen") }
    var tunnels by remember { mutableStateOf(emptyList<CloudflareTunnel>()) }
    var domains by remember { mutableStateOf(emptyList<String>()) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(l.text("公网隧道","Public tunnel","公網通道"),fontWeight=FontWeight.Bold)
        Text(tunnelLabel(l,tunnel.mode)+" · "+phaseLabel(l,tunnel.phase))
        tunnel.publicAddress?.let { address -> Text(address); Action(l.text("复制地址","Copy address","複製位址")) { copy(app,address) } }
        tunnel.errorCode?.let { Text(localizedError(l,it),color=MiuixTheme.colorScheme.error) }
        WindowDropdownPreference(items=listOf(l.text("关闭","Off","關閉"),"frpc",l.text("Cloudflare 随机地址","Cloudflare Quick Tunnel","Cloudflare 隨機位址"),l.text("Cloudflare 固定域名","Cloudflare named tunnel","Cloudflare 固定網域")),selectedIndex=mode.ordinal,title=l.text("隧道方式","Tunnel mode","通道方式"),onSelectedIndexChange={ index -> mode=TunnelMode.entries[index]; task { app.tunnels.stop() } })
        if(mode == TunnelMode.FRPC) {
            Action(l.text(if(advanced) "切换表单" else "导入 TOML",if(advanced) "Use form" else "Import TOML",if(advanced) "切換表單" else "匯入 TOML")) { advanced=!advanced }
            if(advanced) Field("TOML",toml,multiline=true,secret=true) { toml=it }
            else {
                Field(l.text("服务器地址","Server address","伺服器位址"),host) { host=it }
                Field(l.text("服务器端口","Server port","伺服器連接埠"),port) { port=it }
                Field(l.text("认证 Token","Authentication token","驗證 Token"),frpToken,secret=true) { frpToken=it }
                WindowDropdownPreference(items=listOf("TCP","HTTP","HTTPS"),selectedIndex=listOf("tcp","http","https").indexOf(protocol).coerceAtLeast(0),title=l.text("代理类型","Proxy type","代理類型"),onSelectedIndexChange={ protocol=listOf("tcp","http","https")[it] })
                Text(l.text("代理类型：","Proxy type: ","代理類型：")+protocol)
                if(protocol == "tcp") Field(l.text("公网端口","Public port","公網連接埠"),remotePort) { remotePort=it }
                else Field(l.text("公网域名","Public hostname","公網網域"),frpDomain) { frpDomain=it }
            }
            if(protocol != "tcp" || advanced) Field(l.text("公网 HTTP/HTTPS 端口（空为默认）","Public HTTP/HTTPS port (blank uses default)","公網 HTTP/HTTPS 連接埠（空為預設）"),publicPort) { publicPort=it }
            if(protocol == "https" || advanced) {
                Field(l.text("HTTPS 证书 PEM","HTTPS certificate PEM","HTTPS 憑證 PEM"),certificate,multiline=true) { certificate=it }
                Field(l.text("HTTPS 私钥 PEM","HTTPS private key PEM","HTTPS 私鑰 PEM"),privateKey,secret=true,multiline=true) { privateKey=it }
            }
        }
        if(mode == TunnelMode.CLOUDFLARE_NAMED) {
            Field(l.text("已配置的域名","Configured hostname","已設定的網域"),cfHost) { cfHost=it }
            Field(l.text("导入 Tunnel Token","Import tunnel token","匯入 Tunnel Token"),cfToken,secret=true) { cfToken=it }
            Text(l.text("Token 模式需在 Cloudflare 配置服务地址为 http://127.0.0.1:8732。也可使用下方官方浏览器授权。","For token mode, configure the origin as http://127.0.0.1:8732 in Cloudflare. Alternatively authorize using the official browser flow below.","Token 模式需在 Cloudflare 設定服務位址為 http://127.0.0.1:8732。也可使用下方官方瀏覽器授權。"))
            Action(l.text("官方浏览器授权","Authorize with Cloudflare","官方瀏覽器授權")) { foreground(app); task { app.tunnels.login() } }
            tunnel.loginUrl?.let { url -> Action(l.text("打开授权页面","Open authorization page","開啟授權頁面")) { app.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            if(tunnel.certificateReady) {
                Text(l.text("授权证书已取得","Authorization certificate ready","授權憑證已取得"))
                Action(l.text("列出隧道与域名","List tunnels and domains","列出通道與網域")) { task { tunnels=app.tunnels.listTunnels(); domains=app.tunnels.availableDomains() } }
                tunnels.forEach { item -> Action(item.name) { tunnelId=item.id; task { app.tunnels.obtainTunnelCredentials(item.id) } } }
                domains.forEach { Text(l.text("可用域名：","Available zone: ","可用網域：")+it) }
                Field(l.text("隧道 ID","Tunnel ID","通道 ID"),tunnelId) { tunnelId=it }
                Field(l.text("新隧道名称","New tunnel name","新通道名稱"),tunnelName) { tunnelName=it }
                Action(l.text("创建隧道","Create tunnel","建立通道")) { task { val item=app.tunnels.createTunnel(tunnelName); tunnelId=item.id } }
                Action(l.text("创建 DNS 路由","Create DNS route","建立 DNS 路由")) { task { app.tunnels.bindDns(tunnelId,cfHost) } }
            }
        }
        if(mode != TunnelMode.OFF) Action(l.text("启动隧道","Start tunnel","啟動通道"),server.running) { task {
            val frpc=if(mode == TunnelMode.FRPC) FrpcConfig(host,port.toInt(),frpToken.takeIf(String::isNotBlank),protocol=protocol,remotePort=remotePort.toInt(),hostname=frpDomain,publicPort=publicPort.toIntOrNull(),advancedToml=toml.takeIf { advanced && it.isNotBlank() },certificatePem=certificate.takeIf(String::isNotBlank),privateKeyPem=privateKey.takeIf(String::isNotBlank)) else null
            val cf=if(mode == TunnelMode.CLOUDFLARE_NAMED) CloudflareConfig(cfToken.takeIf(String::isNotBlank),cfHost,tunnelId.takeIf(String::isNotBlank)) else null
            val result=app.tunnels.start(mode,frpc,cf); check(result.errorCode==null) { result.errorCode!! }
        } }
        if(tunnel.logs.isNotEmpty()) Action(l.text("复制连接诊断","Copy connection diagnostics","複製連線診斷")) { copy(app,tunnel.logs.takeLast(8).joinToString("\n")) }
    } }
}

@Composable internal fun Action(label: String,enabled: Boolean = true,onClick: () -> Unit) { TextButton(text=label,onClick=onClick,enabled=enabled,
    colors=top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(color=Color.Transparent,textColor=MiuixTheme.colorScheme.primary)) }
@Composable internal fun Field(label: String,value: String,secret: Boolean = false,multiline: Boolean = false,onValue: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    LaunchedEffect(value) { if(field.text != value) field=TextFieldValue(value) }
    TextField(value=field,onValueChange={ field=it; onValue(it.text.take(if(multiline) 65536 else 20000)) },label=label,
        singleLine=!multiline,maxLines=if(multiline) 6 else 1,
        visualTransformation=if(secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier=Modifier.fillMaxWidth())
}
internal fun copy(context: Context,text: String) {
    val clip=ClipData.newPlainText(context.getString(io.github.lejw0925.virtualscreen.R.string.app_name),text)
    clip.description.extras=android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE,true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
internal fun connectionAddress(app: VirtualScreenApplication): String {
    val server=app.mcp.state()
    return app.tunnels.state().publicAddress?.replaceFirst("tcp://","http://")
        ?: (if(server.host=="0.0.0.0") lanAddresses(server.port).firstOrNull() else null)
        ?: "http://127.0.0.1:${server.port}/mcp"
}
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
    "reconnecting" -> l.text("重新连接中","Reconnecting","重新連線中")
    "login", "logging_in" -> l.text("等待官方授权","Awaiting authorization","等待官方授權")
    "credentials_ready", "certificate_ready" -> l.text("凭据已就绪","Credentials ready","憑證已就緒")
    "tunnel_configuring" -> l.text("正在配置隧道","Configuring tunnel","正在設定通道")
    "dns_binding" -> l.text("正在绑定域名","Binding hostname","正在繫結網域")
    "dns_ready" -> l.text("域名已绑定","Hostname ready","網域已繫結")
    "error", "failed" -> l.text("连接失败","Connection failed","連線失敗")
    else -> l.text("未连接","Disconnected","未連線")
}
