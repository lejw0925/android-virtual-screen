package io.github.lejw0925.virtualscreen.ui

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.core.code
import io.github.lejw0925.virtualscreen.tunnel.*
import kotlinx.coroutines.CancellationException
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable internal fun TunnelSettings(
    app: VirtualScreenApplication,
    task: (suspend () -> Unit) -> Unit,
    notifications: () -> Unit,
    busy: Boolean,
) {
    val l = LocalLanguage.current
    val server by app.mcp.flow.collectAsState()
    val mcpChanging by app.mcpChanging.collectAsState()
    val tunnel by app.tunnels.flow.collectAsState()
    val toggleMcp = rememberMcpToggle(app, notifications)
    val loaded = remember { app.tunnels.loadProfile() }
    val modes = listOf(TunnelMode.CLOUDFLARE_QUICK, TunnelMode.CLOUDFLARE_NAMED, TunnelMode.FRPC)
    var mode by remember {
        mutableStateOf(tunnel.mode.takeUnless { it == TunnelMode.OFF }
            ?: when {
                loaded.second != null -> TunnelMode.CLOUDFLARE_NAMED
                loaded.first != null -> TunnelMode.FRPC
                else -> TunnelMode.CLOUDFLARE_QUICK
            })
    }
    val frpc = remember { FrpcForm(loaded.first) }
    var cfToken by remember { mutableStateOf(loaded.second?.tunnelToken ?: "") }
    var cfHost by remember { mutableStateOf(loaded.second?.hostname ?: "") }
    var tunnelId by remember { mutableStateOf(loaded.second?.tunnelId ?: "") }
    var useToken by remember { mutableStateOf(cfToken.isNotBlank() || tunnelId.isBlank()) }
    var showCloudflare by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var availableTunnels by remember { mutableStateOf(emptyList<CloudflareTunnel>()) }
    var domains by remember { mutableStateOf(emptyList<String>()) }
    var taskError by remember { mutableStateOf<String?>(null) }
    fun runTask(block: suspend () -> Unit) {
        task {
            taskError = null
            try { block() }
            catch(c: CancellationException) { throw c }
            catch(e: Exception) { taskError = e.code() }
        }
    }
    val error = taskError ?: tunnel.errorCode.takeIf { tunnel.phase == "error" } ?: server.lastError.takeUnless { server.running }
    val savedCertificate = remember { app.tunnels.hasCertificate() }
    val certificateReady = tunnel.certificateReady || savedCertificate
    val loggingIn = tunnel.phase == "login"
    val progressComplete = tunnel.phase == "connected" || tunnel.phase == "dns_ready"
    val progressActive = error == null && !progressComplete &&
        (busy || mcpChanging || tunnel.running || loggingIn || tunnel.phase in setOf("dns_binding", "tunnel_configuring"))
    val editable = !busy && !tunnel.running
    val configurationReady = when(mode) {
        TunnelMode.CLOUDFLARE_QUICK -> true
        TunnelMode.CLOUDFLARE_NAMED -> cfHost.isNotBlank() && if(useToken) cfToken.isNotBlank() else tunnelId.isNotBlank()
        TunnelMode.FRPC -> if(frpc.advanced) frpc.toml.isNotBlank() else frpc.host.isNotBlank()
        TunnelMode.OFF -> false
    }

    Section(l.text("连接","Connection","連線")) {
        WindowDropdownPreference(
            title = l.text("隧道方式","Tunnel mode","通道方式"),
            items = listOf(l.text("Cloudflare 随机地址","Cloudflare Quick Tunnel","Cloudflare 隨機位址"),
                l.text("Cloudflare 固定域名","Cloudflare named tunnel","Cloudflare 固定網域"), "frpc"),
            selectedIndex = modes.indexOf(mode),
            enabled = !busy,
            onSelectedIndexChange = { index ->
                if(mode != modes[index]) {
                    mode = modes[index]
                    app.tunnelWanted = TunnelMode.OFF
                    runTask { app.tunnels.stop() }
                }
            },
        )
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusChip(
                phaseLabel(l, tunnel.phase),
                tone = when {
                    tunnel.phase == "connected" -> StatusTone.Positive
                    tunnel.phase == "failed" || error != null -> StatusTone.Negative
                    else -> StatusTone.Neutral
                },
            )
            error?.let { Text(localizedError(l, it), color = MaterialTheme.colorScheme.error) }
            if (progressActive) {
                LinearWavyProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            } else {
                LinearWavyProgressIndicator(
                    progress = { if (progressComplete) 1f else 0f },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = when {
                        error != null -> MaterialTheme.colorScheme.error
                        progressComplete -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    amplitude = { 1f },
                    waveSpeed = 0.dp,
                )
            }
            TunnelButton(
                text = when {
                    tunnel.running -> l.text("停止隧道","Stop tunnel","停止通道")
                    loggingIn -> l.text("取消授权","Cancel authorization","取消授權")
                    mcpChanging -> l.text("正在开启 MCP…","Starting MCP…","正在開啟 MCP…")
                    !server.running -> l.text("开启 MCP 服务","Enable MCP","開啟 MCP 服務")
                    else -> l.text("连接隧道","Connect tunnel","連線通道")
                },
                enabled = !mcpChanging && (!busy || tunnel.running || loggingIn) &&
                    (tunnel.running || loggingIn || !server.running || configurationReady),
            ) {
                when {
                    tunnel.running || loggingIn -> { app.tunnelWanted = TunnelMode.OFF; runTask { app.tunnels.stop() } }
                    !server.running -> toggleMcp(true)
                    else -> {
                        val selectedMode = mode
                        val frpcConfig = if(selectedMode == TunnelMode.FRPC) frpc.config() else null
                        val cfConfig = if(selectedMode == TunnelMode.CLOUDFLARE_NAMED)
                            CloudflareConfig(if(useToken) cfToken.takeIf(String::isNotBlank) else null,
                                cfHost, tunnelId.takeIf(String::isNotBlank)) else null
                        app.tunnelWanted = selectedMode
                        runTask { app.tunnels.start(selectedMode, frpcConfig, cfConfig) }
                    }
                }
            }
        }
        tunnel.publicAddress?.let { address ->
            ArrowPreference(title = l.text("复制公网地址","Copy public address","複製公網位址"),
                summary = address, onClick = { copy(app, address) })
            ArrowPreference(title = l.text("复制 MCP 连接配置","Copy MCP configuration","複製 MCP 連線設定"),
                onClick = { copy(app, app.mcp.clientConfiguration(address.replaceFirst("tcp://", "http://"))) })
            ArrowPreference(title = l.text("检查公网连接","Check public connection","檢查公網連線"),
                onClick = { app.startActivity(Intent(Intent.ACTION_VIEW, address.replaceFirst("tcp://", "http://").substringBefore("/mcp").toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        }
    }

    when(mode) {
        TunnelMode.CLOUDFLARE_QUICK -> Unit
        TunnelMode.CLOUDFLARE_NAMED -> Section(l.text("连接配置","Connection settings","連線設定")) {
            WindowDropdownPreference(
                title = l.text("授权方式","Authorization","授權方式"),
                items = listOf("Tunnel Token", l.text("浏览器授权","Browser authorization","瀏覽器授權")),
                selectedIndex = if(useToken) 0 else 1, enabled = editable,
                onSelectedIndexChange = { useToken = it == 0 },
            )
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(l.text("公网域名","Public hostname","公網網域"), cfHost, enabled = editable) { cfHost = it }
                if(useToken) {
                    Field("Tunnel Token", cfToken, secret = true, enabled = editable) { cfToken = it }
                } else {
                    (availableTunnels.firstOrNull { it.id == tunnelId }?.name ?: tunnelId.takeIf(String::isNotBlank))
                        ?.let { TunnelStatus(it) }
                }
            }
            if(!useToken) ArrowPreference(title = l.text("Cloudflare 账号与域名","Cloudflare account and hostname","Cloudflare 帳號與網域"),
                summary = if(certificateReady) l.text("已授权","Authorized","已授權")
                    else l.text("未授权","Not authorized","未授權"),
                enabled = !tunnel.running, onClick = { showCloudflare = true })
        }
        TunnelMode.FRPC -> Section(l.text("连接配置","Connection settings","連線設定")) {
            WindowDropdownPreference(title = l.text("配置方式","Configuration","設定方式"),
                items = listOf(l.text("表单","Form","表單"), l.text("导入 TOML","Import TOML","匯入 TOML")),
                selectedIndex = if(frpc.advanced) 1 else 0, enabled = editable,
                onSelectedIndexChange = { frpc.advanced = it == 1 })
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if(frpc.advanced) Field("TOML", frpc.toml, secret = true, multiline = true, enabled = editable) { frpc.toml = it }
                else {
                    Field(l.text("服务器地址","Server address","伺服器位址"), frpc.host, enabled = editable) { frpc.host = it }
                    Field(l.text("服务器端口","Server port","伺服器連接埠"), frpc.port, enabled = editable) { frpc.port = it }
                    Field(l.text("认证 Token","Authentication token","驗證 Token"), frpc.token, secret = true, enabled = editable) { frpc.token = it }
                    WindowDropdownPreference(title = l.text("代理类型","Proxy type","代理類型"), items = listOf("TCP", "HTTP", "HTTPS"),
                        selectedIndex = listOf("tcp", "http", "https").indexOf(frpc.protocol).coerceAtLeast(0), enabled = editable,
                        onSelectedIndexChange = { frpc.protocol = listOf("tcp", "http", "https")[it] })
                    if(frpc.protocol == "tcp") Field(l.text("公网端口","Public port","公網連接埠"), frpc.remotePort, enabled = editable) { frpc.remotePort = it }
                    else Field(l.text("公网域名","Public hostname","公網網域"), frpc.domain, enabled = editable) { frpc.domain = it }
                }
            }
            if(frpc.protocol != "tcp" || frpc.advanced) ArrowPreference(title = l.text("端口与 HTTPS 证书","Ports and HTTPS certificate","連接埠與 HTTPS 憑證"),
                enabled = editable, onClick = { showAdvanced = true })
        }
        TunnelMode.OFF -> Unit
    }
    Section(l.text("隧道日志", "Tunnel logs", "通道日誌")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(tunnel.logs.isEmpty()) TunnelStatus(l.text("暂无日志", "No logs", "尚無日誌"))
            else {
                LazyColumn(Modifier.fillMaxWidth().height(220.dp), reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(tunnel.logs.asReversed()) { line ->
                        SelectionContainer { Text(line, style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface) }
                    }
                }
                OutlinedButton(onClick = { showDiagnostics = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(l.text("查看与复制完整日志", "View and copy full logs", "檢視與複製完整日誌"))
                }
            }
        }
    }

    if(showCloudflare) TunnelDialog(l.text("Cloudflare 配置","Cloudflare setup","Cloudflare 設定"), { showCloudflare = false }) {
        if(busy) TunnelStatus(phaseLabel(l, tunnel.phase))
        error?.let { Text(localizedError(l, it), color = MaterialTheme.colorScheme.error) }
        TunnelStep(l.text("1. 授权账号","1. Authorize account","1. 授權帳號"))
        if(!certificateReady) {
            TunnelButton(if(tunnel.loginUrl != null) l.text("打开授权页面","Open authorization page","開啟授權頁面")
                else if(loggingIn) l.text("正在生成授权链接…","Preparing authorization link…","正在產生授權連結…")
                else l.text("官方浏览器授权","Authorize with Cloudflare","官方瀏覽器授權"),
                enabled = tunnel.loginUrl != null || !busy) {
                val url = tunnel.loginUrl
                if(url != null) app.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else { foreground(app); runTask { app.tunnels.login() } }
            }
        } else {
            TunnelStatus(l.text("账号已授权","Account authorized","帳號已授權"))
            TunnelStep(l.text("2. 选择隧道","2. Select tunnel","2. 選擇通道"))
            var createNew by remember { mutableStateOf(false) }
            var name by remember { mutableStateOf("android-virtual-screen") }
            WindowDropdownPreference(title = l.text("使用隧道","Tunnel","使用通道"),
                items = listOf(l.text("已有隧道","Existing tunnel","現有通道"), l.text("新建隧道","Create tunnel","新建通道")),
                selectedIndex = if(createNew) 1 else 0, enabled = !busy, onSelectedIndexChange = { createNew = it == 1 })
            if(createNew) {
                Field(l.text("隧道名称","Tunnel name","通道名稱"), name, enabled = !busy) { name = it }
                TunnelButton(l.text("创建隧道","Create tunnel","建立通道"), !busy && name.isNotBlank()) {
                    val newName = name
                    runTask {
                        val item = app.tunnels.createTunnel(newName)
                        availableTunnels = availableTunnels.filterNot { it.id == item.id } + item
                        tunnelId = item.id
                        createNew = false
                    }
                }
            } else {
                if(availableTunnels.isNotEmpty()) WindowDropdownPreference(
                    title = l.text("选择隧道","Select tunnel","選擇通道"),
                    items = listOf(l.text("请选择","Select…","請選擇")) + availableTunnels.map { it.name },
                    selectedIndex = availableTunnels.indexOfFirst { it.id == tunnelId } + 1, enabled = !busy,
                    onSelectedIndexChange = { index -> if(index > 0) {
                        val item = availableTunnels[index - 1]
                        runTask { app.tunnels.obtainTunnelCredentials(item.id); tunnelId = item.id }
                    } },
                )
                Field(l.text("隧道 ID","Tunnel ID","通道 ID"), tunnelId, enabled = !busy) { tunnelId = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action(l.text("刷新列表","Refresh list","重新整理清單"), !busy) { runTask {
                        availableTunnels = app.tunnels.listTunnels()
                        domains = app.tunnels.availableDomains()
                    } }
                    Action(l.text("读取凭据","Fetch credentials","讀取憑證"), !busy && tunnelId.isNotBlank()) {
                        val id = tunnelId
                        runTask { app.tunnels.obtainTunnelCredentials(id) }
                    }
                }
            }
            if(tunnelId.isNotBlank()) {
                TunnelStep(l.text("3. 绑定域名","3. Bind hostname","3. 繫結網域"))
                Field(l.text("公网域名","Public hostname","公網網域"), cfHost, enabled = !busy) { cfHost = it }
                if(domains.isNotEmpty()) TunnelStatus(l.text("可用域名：","Available zone: ","可用網域：") + domains.joinToString(", "))
                TunnelButton(l.text("绑定域名","Bind hostname","繫結網域"), !busy && cfHost.isNotBlank()) {
                    val id = tunnelId
                    val hostname = cfHost
                    runTask { app.tunnels.bindDns(id, hostname) }
                }
                if(tunnel.phase == "dns_ready") TunnelStatus(phaseLabel(l, tunnel.phase))
            }
        }
    }
    if(showAdvanced) TunnelDialog(l.text("端口与 HTTPS 证书","Ports and HTTPS certificate","連接埠與 HTTPS 憑證"), { showAdvanced = false }) {
        Field(l.text("公网 HTTP/HTTPS 端口","Public HTTP/HTTPS port","公網 HTTP/HTTPS 連接埠"),
            frpc.publicPort, enabled = editable) { frpc.publicPort = it }
        if(frpc.protocol == "https" || frpc.advanced) {
            Field(l.text("HTTPS 证书 PEM","HTTPS certificate PEM","HTTPS 憑證 PEM"), frpc.certificate, multiline = true, enabled = editable) { frpc.certificate = it }
            Field(l.text("HTTPS 私钥 PEM","HTTPS private key PEM","HTTPS 私鑰 PEM"), frpc.privateKey, secret = true, multiline = true, enabled = editable) { frpc.privateKey = it }
        }
    }
    if(showDiagnostics) TunnelDialog(l.text("连接诊断","Connection diagnostics","連線診斷"), { showDiagnostics = false }) {
        val logs = tunnel.logs.joinToString("\n")
        TunnelButton(l.text("复制诊断日志","Copy diagnostics","複製診斷日誌")) { copy(app, logs) }
        SelectionContainer { Text(logs, style = MaterialTheme.typography.bodySmall) }
    }
}

private class FrpcForm(config: FrpcConfig?) {
    private val proxyName = config?.proxyName ?: "virtual-screen-mcp"
    var host by mutableStateOf(config?.serverAddress ?: "")
    var port by mutableStateOf((config?.serverPort ?: 7000).toString())
    var token by mutableStateOf(config?.token ?: "")
    var protocol by mutableStateOf(config?.protocol ?: "tcp")
    var remotePort by mutableStateOf((config?.remotePort ?: 8732).toString())
    var domain by mutableStateOf(config?.hostname ?: "")
    var toml by mutableStateOf(config?.advancedToml ?: "")
    var advanced by mutableStateOf(!config?.advancedToml.isNullOrBlank())
    var certificate by mutableStateOf(config?.certificatePem ?: "")
    var privateKey by mutableStateOf(config?.privateKeyPem ?: "")
    var publicPort by mutableStateOf(config?.publicPort?.toString() ?: "")
    fun config() = FrpcConfig(host, port.toIntOrNull() ?: 0, token.takeIf(String::isNotBlank), proxyName = proxyName, protocol = protocol,
        remotePort = remotePort.toIntOrNull() ?: 0, hostname = domain, publicPort = publicPort.toIntOrNull(),
        advancedToml = toml.takeIf { advanced && it.isNotBlank() }, certificatePem = certificate.takeIf(String::isNotBlank),
        privateKeyPem = privateKey.takeIf(String::isNotBlank))
}

@Composable private fun TunnelStatus(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun TunnelStep(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurface)
}

@Composable private fun TunnelButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.AutoMirrored.Filled.ArrowForward,null)
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

@Composable private fun TunnelDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val l = LocalLanguage.current
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.heightIn(max = 640.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, shape = RoundedCornerShape(12.dp)) { Text(l.text("完成","Done","完成")) }
                }
            }
        }
    }
}
