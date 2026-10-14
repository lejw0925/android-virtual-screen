package io.github.lejw0925.virtualscreen.ui

import android.Manifest
import android.app.LocaleManager
import android.content.Intent
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.lejw0925.virtualscreen.BuildConfig
import io.github.lejw0925.virtualscreen.R
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.VirtualScreenForegroundService
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

class SettingsActivity : ComponentActivity() {
    private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        val app=application as VirtualScreenApplication
        prepareWindowTheme(app)
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { VirtualScreenTheme(app) { SettingsContent(app,intent.getStringExtra("page") ?: "HOME",{ notifications.launch(Manifest.permission.POST_NOTIFICATIONS) },{ finish() }) } }
    }
    override fun finish() {
        super.finish()
        if(android.os.Build.VERSION.SDK_INT<34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.avs_slide_in_left,R.anim.avs_slide_out_right)
        }
    }
}
private enum class SettingsPage { HOME,BACKEND,DISPLAY,MCP,TUNNEL,APPEARANCE,BACKGROUND,ABOUT,LICENSES,GUIDE }
@Composable private fun SettingsContent(app: VirtualScreenApplication,initial: String,notifications: () -> Unit,onExit: () -> Unit) {
    val l=LocalLanguage.current
    val background=MiuixTheme.colorScheme.surface
    WindowAppearance(background)
    var stack by rememberSaveable { mutableStateOf(listOf(SettingsPage.HOME).let { pages ->
        val destination=runCatching { SettingsPage.valueOf(initial) }.getOrDefault(SettingsPage.HOME)
        if(destination==SettingsPage.HOME) pages else pages+destination
    }) }
    var forward by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    val task: (suspend () -> Unit) -> Unit={ block -> scope.launch { busy=true; try { withContext(Dispatchers.IO) { app.awaitShutdown(); block() } } catch(c: CancellationException) { throw c } catch(e: Exception) { error=e.code() } finally { busy=false } } }
    fun go(page: SettingsPage) { forward=true; stack=stack+page }
    fun back() { if(stack.size>1) { forward=false; stack=stack.dropLast(1) } else onExit() }
    BackHandler { back() }
    AnimatedContent(targetState=stack.last(),modifier=Modifier.fillMaxSize().background(background),transitionSpec={
        val direction=if(forward) 1 else -1
        (slideInHorizontally(tween(260)) { it*direction/4 }+fadeIn(tween(220))) togetherWith
            (slideOutHorizontally(tween(240)) { -it*direction/6 }+fadeOut(tween(180)))
    },label="settings navigation") { page ->
        val scroll=MiuixScrollBehavior()
        Scaffold(containerColor=MiuixTheme.colorScheme.surface,topBar={ TopAppBar(title=pageTitle(l,page),color=MiuixTheme.colorScheme.surface,scrollBehavior=scroll,titlePadding=20.dp,navigationIconPadding=20.dp,
            navigationIcon={ IconButton(onClick={ back() },modifier=Modifier.size(48.dp),backgroundColor=MiuixTheme.colorScheme.surfaceContainer,cornerRadius=24.dp) { Icon(MiuixIcons.ChevronBackward,l.text("返回","Back","返回"),modifier=Modifier.size(24.dp)) } }) }) { padding ->
            Column(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection).verticalScroll(rememberScrollState()).padding(padding).padding(horizontal=20.dp).padding(bottom=28.dp).imePadding(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                if(busy) Text(l.text("正在处理…","Working…","正在處理…"),style=MiuixTheme.textStyles.footnote1)
                error?.let { code -> Card { Column(Modifier.padding(18.dp)) { Text(localizedError(l,code),color=MiuixTheme.colorScheme.error); Action(l.text("关闭","Dismiss","關閉")) { error=null } } } }
                when(page) {
                    SettingsPage.HOME -> SettingsHome(app,::go)
                    SettingsPage.BACKEND -> BackendPreferences(app,task)
                    SettingsPage.DISPLAY -> DisplayPreferences(app,task)
                    SettingsPage.MCP -> McpPreferences(app,task,notifications)
                    SettingsPage.TUNNEL -> TunnelSettings(app,task,notifications,busy)
                    SettingsPage.APPEARANCE -> AppearancePreferences(app)
                    SettingsPage.BACKGROUND -> BackgroundPreferences(app,task)
                    SettingsPage.ABOUT -> AboutPreferences(app) { go(SettingsPage.LICENSES) }
                    SettingsPage.LICENSES -> LicenseText(app)
                    SettingsPage.GUIDE -> SetupGuide(app,notifications,scrollable=false) { back() }
                }
            }
        }
    }
}
private fun pageTitle(l: UiLanguage,page: SettingsPage)=when(page) {
    SettingsPage.HOME -> l.text("设置","Settings","設定")
    SettingsPage.BACKEND -> l.text("权限与后端","Permissions and backend","權限與後端")
    SettingsPage.DISPLAY -> l.text("显示与控制","Display and control","顯示與控制")
    SettingsPage.MCP -> l.text("MCP 连接","MCP connection","MCP 連線")
    SettingsPage.TUNNEL -> l.text("公网隧道","Public tunnel","公網通道")
    SettingsPage.APPEARANCE -> l.text("外观与语言","Appearance and language","外觀與語言")
    SettingsPage.BACKGROUND -> l.text("后台运行","Background operation","背景執行")
    SettingsPage.ABOUT -> l.text("关于","About","關於")
    SettingsPage.LICENSES -> l.text("开源许可与构建来源","Licenses and build sources","開源授權與建置來源")
    SettingsPage.GUIDE -> l.text("使用指南","Getting started","使用指南")
}
@Composable internal fun Section(label: String,content: @Composable () -> Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(label,modifier=Modifier.padding(horizontal=14.dp),style=MiuixTheme.textStyles.footnote1,color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Card(Modifier.fillMaxWidth()) { content() }
    }
}
@Composable private fun SettingsHome(app: VirtualScreenApplication,go: (SettingsPage) -> Unit) {
    val l=LocalLanguage.current; val s by app.sessions.state.collectAsState(); val server by app.mcp.flow.collectAsState(); val tunnel by app.tunnels.flow.collectAsState()
    Section(l.text("虚拟屏","Virtual screen","虛擬螢幕")) {
        ArrowPreference(title=pageTitle(l,SettingsPage.BACKEND),summary=s.backend.wireName.uppercase(),onClick={ go(SettingsPage.BACKEND) })
        ArrowPreference(title=pageTitle(l,SettingsPage.DISPLAY),summary=l.text("分辨率、DPI、旋转和控制权","Resolution, DPI, rotation and control","解析度、DPI、旋轉和控制權"),onClick={ go(SettingsPage.DISPLAY) })
    }
    Section(l.text("连接","Connections","連線")) {
        ArrowPreference(title=pageTitle(l,SettingsPage.MCP),summary=if(server.running) l.text("运行中 · ${server.sessionCount} 个客户端","Running · ${server.sessionCount} clients","執行中 · ${server.sessionCount} 個用戶端") else l.text("本机与局域网访问","Local and LAN access","本機與區域網路存取"),onClick={ go(SettingsPage.MCP) })
        ArrowPreference(title=pageTitle(l,SettingsPage.TUNNEL),summary=tunnelLabel(l,tunnel.mode)+" · "+phaseLabel(l,tunnel.phase),onClick={ go(SettingsPage.TUNNEL) })
    }
    Section(l.text("应用","Application","應用程式")) {
        ArrowPreference(title=pageTitle(l,SettingsPage.APPEARANCE),summary=l.text("主题、动态配色和语言","Theme, dynamic colors and language","主題、動態配色和語言"),onClick={ go(SettingsPage.APPEARANCE) })
        ArrowPreference(title=pageTitle(l,SettingsPage.BACKGROUND),summary=l.text("空闲释放与电池设置","Idle release and battery settings","閒置釋放與電池設定"),onClick={ go(SettingsPage.BACKGROUND) })
        ArrowPreference(title=pageTitle(l,SettingsPage.ABOUT),summary=BuildConfig.VERSION_NAME,onClick={ go(SettingsPage.ABOUT) })
        ArrowPreference(title=pageTitle(l,SettingsPage.GUIDE),summary=l.text("授权、连接与接管","Access, connection and takeover","授權、連線與接管"),onClick={ go(SettingsPage.GUIDE) })
    }
}
@Composable private fun BackendPreferences(app: VirtualScreenApplication,task: (suspend () -> Unit) -> Unit) {
    val l=LocalLanguage.current; val state by app.sessions.state.collectAsState()
    Section(l.text("授权","Authorization","授權")) {
        WindowDropdownPreference(items=listOf("Root","Shizuku"),selectedIndex=state.backend.ordinal,title=l.text("权限后端","Backend","權限後端"),onSelectedIndexChange={ index -> task { val backend=BackendKind.entries[index]; app.sessions.selectBackend(backend); app.settings.update { it.copy(backend=backend) } } })
        ArrowPreference(title=l.text("授权并检测","Authorize and probe","授權並檢測"),summary=l.text("授权后自动启用虚拟屏节点服务","Authorization automatically enables virtual-display nodes","授權後自動啟用虛擬螢幕節點服務"),onClick={ task { app.sessions.requestAuthorization() } })
        ArrowPreference(title=l.text("画中画预览","Picture-in-picture preview","子母畫面預覽"),summary=l.text("允许 Agent 创建屏幕后自动展示进度","Allow automatic previews when your agent starts a display","允許 Agent 建立螢幕後自動顯示進度"),onClick={
            val intent=Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS",android.net.Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if(intent.resolveActivity(app.packageManager)!=null) app.startActivity(intent) else app.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
    }
    Section(l.text("检测结果","Probed capabilities","檢測結果")) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            val c=state.capabilities
            listOf(l.text("显示创建","Display creation","顯示建立") to c.displayCreation,l.text("截图","Capture","截圖") to c.frameCapture,l.text("输入","Input","輸入") to c.inputInjection,l.text("节点","Nodes","節點") to c.accessibilityNodes,l.text("熄屏运行","Screen-off operation","熄屏執行") to c.screenOff).forEach { (name,value) ->
                Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Text(name,modifier=Modifier.weight(.56f).padding(end=12.dp))
                    Text(capabilityLabel(l,value),modifier=Modifier.weight(.44f),textAlign=androidx.compose.ui.text.style.TextAlign.End,color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        ArrowPreference(title=l.text("重新检测","Probe again","重新檢測"),onClick={ task { app.sessions.refreshCapabilities() } })
    }
    Hint(l.text("节点不可用时仍可使用截图和坐标。熄屏能力在实际操作后确认。","Use screenshots and coordinates when nodes are unavailable. Screen-off capability is confirmed by actual operation.","節點不可用時仍可使用截圖和座標。熄屏能力在實際操作後確認。"))
}
private fun capabilityLabel(l: UiLanguage,c: CapabilityStatus)=when(c) {
    CapabilityStatus.AVAILABLE -> l.text("可用","Available","可用")
    CapabilityStatus.REQUIRES_AUTHORIZATION -> l.text("需要授权","Authorize","需要授權")
    CapabilityStatus.UNKNOWN -> l.text("待实际检测","Not yet probed","待實際檢測")
    CapabilityStatus.UNAVAILABLE -> l.text("不可用","Unavailable","不可用")
}
@Composable private fun DisplayPreferences(app: VirtualScreenApplication,task: (suspend () -> Unit) -> Unit) {
    val l=LocalLanguage.current; val settings by app.settings.flow.collectAsState(); val state by app.sessions.state.collectAsState()
    var edit by remember { mutableStateOf(false) }; var restart by remember { mutableStateOf(false) }
    Section(l.text("显示","Display","顯示")) {
        SwitchPreference(checked=settings.fitViewer,onCheckedChange={ value -> app.settings.update { it.copy(fitViewer=value,matchHostDensity=value) } },title=l.text("贴合手机显示区域","Fit the phone viewport","貼合手機顯示區域"),summary=l.text("按手机分辨率和 DPI 保持实际显示密度","Match the phone resolution and DPI for consistent app density","按手機解析度和 DPI 保持實際顯示密度"))
        ArrowPreference(title=l.text("分辨率与 DPI","Resolution and DPI","解析度與 DPI"),summary=app.settings.configForViewer().let { "${it.width} × ${it.height} · ${it.density} dpi" },onClick={ edit=true })
        WindowDropdownPreference(items=listOf("0°","90°","180°","270°"),selectedIndex=settings.config.rotation,title=l.text("初始旋转","Initial rotation","初始旋轉"),onSelectedIndexChange={ index -> app.settings.update { it.copy(config=it.config.copy(rotation=index)) } })
        SwitchPreference(checked=settings.restoreApp,onCheckedChange={ value -> app.settings.update { it.copy(restoreApp=value) } },title=l.text("恢复上次应用","Restore last app","恢復上次應用程式"))
    }
    if(state.displayId!=null) Section(l.text("控制","Control","控制")) {
        ArrowPreference(title=l.text("应用新的显示参数","Apply new display settings","套用新的顯示參數"),summary=l.text("重建屏幕后需重新观察","Recreation requires fresh observations","重建螢幕後需重新觀察"),onClick={ restart=true })
    }
    Hint(l.text("显示参数在下次启动或重建屏幕时生效。","Display settings apply at the next start or recreation.","顯示參數在下次啟動或重建螢幕時生效。"))
    if(edit) Dialog(onDismissRequest={ edit=false }) { Card { Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        val effective=app.settings.configForViewer()
        var width by remember { mutableStateOf(effective.width.toString()) }; var height by remember { mutableStateOf(effective.height.toString()) }; var dpi by remember { mutableStateOf(effective.density.toString()) }
        Text(l.text("显示参数","Display settings","顯示參數"),style=MiuixTheme.textStyles.title3)
        Field(l.text("宽度","Width","寬度"),width) { width=it }; if(!settings.fitViewer) Field(l.text("高度","Height","高度"),height) { height=it }; Field("DPI",dpi) { dpi=it }
        Button(onClick={ task { val c=settings.config.copy(width=width.toInt(),height=height.toInt(),density=dpi.toInt()); app.settings.update { it.copy(config=c,matchHostDensity=false) }; withContext(Dispatchers.Main) { edit=false } } }) { Text(l.text("保存","Save","儲存")) }
    } } }
    if(restart) Confirm(l.text("重建虚拟屏","Recreate virtual screen","重建虛擬螢幕"),l.text("将结束当前显示会话并使远程观察失效。","This ends the current display session and invalidates remote observations.","將結束目前顯示工作階段並使遠端觀察失效。"),onDismiss={ restart=false }) { restart=false; task {
        phoneControl(app) { lease ->
            val result=app.sessions.configure(lease.owner,lease.token,app.settings.configForViewer()); check(result.ok) { result.code ?: "CREATE_FAILED" }
            if(settings.restoreApp && settings.lastComponent.isNotBlank()) {
                val launched=app.sessions.launch(lease.owner,lease.token,settings.lastComponent)
                check(launched.ok) { launched.code ?: "LAUNCH_FAILED" }
            }
        }
    } }
}
@Composable private fun McpPreferences(app: VirtualScreenApplication,task: (suspend () -> Unit) -> Unit,notifications: () -> Unit) {
    val l=LocalLanguage.current; val server by app.mcp.flow.collectAsState(); val settings by app.settings.flow.collectAsState(); val tunnel by app.tunnels.flow.collectAsState()
    val toggleMcp=rememberMcpToggle(app,notifications)
    val alwaysAllow by app.mcp.approvals.alwaysAllow.collectAsState()
    var rotate by remember { mutableStateOf(false) }
    Section(l.text("服务","Server","服務")) {
        SwitchPreference(checked=server.running,onCheckedChange=toggleMcp,title=l.text("MCP 服务","MCP server","MCP 服務"),summary=if(server.running) l.text("已就绪 · ${server.sessionCount} 个客户端","Ready · ${server.sessionCount} clients","已就緒 · ${server.sessionCount} 個用戶端") else l.text("开启后允许 Agent 连接","Enable to connect your agent","開啟後允許 Agent 連線"))
        if(alwaysAllow) ArrowPreference(title=l.text("撤销始终允许接管","Revoke always-allow control","撤銷始終允許接管"),summary=l.text("当前 Token 的 Agent 申请接管手机占用时自动批准","Agents using the current token can request phone-held control automatically","目前 Token 的 Agent 申請接管手機佔用時自動批准"),onClick={ app.mcp.approvals.setAlwaysAllow(false) })
        SwitchPreference(checked=settings.lan,onCheckedChange={ value -> task { app.settings.update { it.copy(lan=value) }; if(server.running) { app.tunnels.stop(); app.mcp.stop(); app.mcp.start(value).getOrThrow() } } },title=l.text("局域网访问","LAN access","區域網路存取"),summary=l.text("所有连接都需要 Bearer Token","Every connection requires a bearer token","所有連線都需要 Bearer Token"))
    }
    Section(l.text("客户端配置","Client configuration","用戶端設定")) {
        ArrowPreference(title=l.text("复制客户端配置","Copy client configuration","複製用戶端設定"),onClick={ copy(app,app.mcp.clientConfiguration(connectionAddress(app))) })
        ArrowPreference(title=l.text("复制 Token","Copy token","複製 Token"),summary="••••••••",onClick={ copy(app,server.token) })
        ArrowPreference(title=l.text("轮换 Token","Rotate token","輪換 Token"),summary=l.text("断开已连接客户端","Disconnect connected clients","中斷已連線用戶端"),onClick={ rotate=true })
        if(server.host=="0.0.0.0") lanAddresses(server.port).forEach { address -> ArrowPreference(title=l.text("局域网地址","LAN address","區域網路位址"),summary=address,onClick={ copy(app,address) }) }
    }
    if(server.clients.isNotEmpty()) Section(l.text("已连接客户端","Connected clients","已連線用戶端")) {
        server.clients.forEach { client -> ArrowPreference(title=client.name,summary=l.text("点按断开会话","Tap to disconnect","點按中斷工作階段"),onClick={ app.mcp.disconnectSession(client.id) }) }
    }
    Hint(l.text("每次工具调用必须说明操作目的，并使用手机系统语言。","Every tool call must explain its purpose in the phone’s system language.","每次工具呼叫必須說明操作目的，並使用手機系統語言。"))
    if(rotate) Confirm(l.text("轮换 Token","Rotate token","輪換 Token"),l.text("旧 Token 将立即失效，当前客户端会断开。","The old token becomes invalid and connected clients will disconnect.","舊 Token 將立即失效，目前用戶端會中斷。"),onDismiss={ rotate=false }) { rotate=false; app.mcp.rotateToken() }
}
@Composable private fun AppearancePreferences(app: VirtualScreenApplication) {
    val l=LocalLanguage.current; val settings by app.settings.flow.collectAsState()
    Section(l.text("外观","Appearance","外觀")) {
        val themes=listOf("MonetSystem","System","Light","Dark")
        WindowDropdownPreference(items=listOf(l.text("动态配色","Dynamic colors","動態配色"),l.text("跟随系统","System","跟隨系統"),l.text("浅色","Light","淺色"),l.text("深色","Dark","深色")),selectedIndex=themes.indexOf(settings.theme).coerceAtLeast(0),title=l.text("主题","Theme","主題"),onSelectedIndexChange={ i -> app.settings.update { it.copy(theme=themes[i]) } })
    }
    Section(l.text("语言","Language","語言")) {
        val languages=listOf("system","zh-Hans","zh-Hant","en")
        WindowDropdownPreference(items=listOf(l.text("跟随系统","System","跟隨系統"),"简体中文","繁體中文","English"),selectedIndex=languages.indexOf(settings.language).coerceAtLeast(0),title=l.text("界面语言","Interface language","介面語言"),onSelectedIndexChange={ i -> val code=languages[i]; app.settings.update { it.copy(language=code) }; app.getSystemService(LocaleManager::class.java).applicationLocales=LocaleList.forLanguageTags(if(code=="system") "" else code) })
    }
    Hint(l.text("MCP 工具说明和操作目的跟随手机系统语言。","MCP descriptions and operation purposes follow the phone’s system language.","MCP 工具說明和操作目的跟隨手機系統語言。"))
}
@Composable private fun BackgroundPreferences(app: VirtualScreenApplication,task: (suspend () -> Unit) -> Unit) {
    val l=LocalLanguage.current; val settings by app.settings.flow.collectAsState(); var stop by remember { mutableStateOf(false) }
    Section(l.text("生命周期","Lifecycle","生命週期")) {
        val minutes=(listOf(0,5,10,20,30,60,120,240)+settings.config.idleTimeoutMinutes).distinct().sorted()
        WindowDropdownPreference(items=minutes.map { if(it==0) l.text("不自动释放","Do not release automatically","不自動釋放") else l.text("$it 分钟","$it minutes","$it 分鐘") },selectedIndex=minutes.indexOf(settings.config.idleTimeoutMinutes),title=l.text("空闲释放","Idle release","閒置釋放"),onSelectedIndexChange={ i -> app.settings.update { it.copy(config=it.config.copy(idleTimeoutMinutes=minutes[i])) } })
        SwitchPreference(checked=settings.config.allowScreenOff,onCheckedChange={ value -> app.settings.update { it.copy(config=it.config.copy(allowScreenOff=value)) } },title=l.text("尝试熄屏运行","Try screen-off operation","嘗試熄屏執行"),summary=l.text("支持情况取决于设备和 ROM","Support depends on the device and ROM","支援情況取決於裝置和 ROM"))
    }
    Section(l.text("系统设置","System settings","系統設定")) {
        ArrowPreference(title=l.text("电池优化","Battery optimization","電池最佳化"),summary=l.text("为持续运行选择不限制","Choose unrestricted for continuous operation","為持續執行選擇不限制"),onClick={ app.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        ArrowPreference(title=l.text("应用后台权限","App background permissions","應用程式背景權限"),summary=l.text("部分设备还需允许后台与自启动","Some devices require background and auto-start permission","部分裝置還需允許背景與自動啟動"),onClick={ app.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        ArrowPreference(title=l.text("停止全部服务","Stop all services","停止所有服務"),onClick={ stop=true })
    }
    if(stop) Confirm(l.text("停止全部服务","Stop all services","停止所有服務"),l.text("关闭虚拟屏、MCP 与隧道。","Close the virtual screen, MCP and tunnel.","關閉虛擬螢幕、MCP 與通道。"),onDismiss={ stop=false }) { stop=false; task { app.stopRuntime(); app.awaitShutdown(); app.stopService(Intent(app,VirtualScreenForegroundService::class.java)) } }
}
@Composable private fun AboutPreferences(app: VirtualScreenApplication,onLicenses: () -> Unit) {
    val l=LocalLanguage.current
    Section(l.text("虚拟屏","Virtual Screen","虛擬屏")) {
        Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text(BuildConfig.VERSION_NAME,style=MiuixTheme.textStyles.title3); Text(l.text("一个由你控制的独立 Android 屏幕","An independent Android screen under your control","一個由你控制的獨立 Android 螢幕"),color=MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        ArrowPreference(title=l.text("开源许可与构建来源","Licenses and build sources","開源授權與建置來源"),summary="ETA · miuix 0.9.4 · frpc 0.65.0 · cloudflared 2025.8.1",onClick=onLicenses)
    }
}
@Composable private fun LicenseText(app: VirtualScreenApplication) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(app) { text=withContext(Dispatchers.IO) { app.assets.list("licenses").orEmpty().joinToString("\n\n") { name -> name.removeSuffix(".txt")+"\n\n"+app.assets.open("licenses/$name").bufferedReader().use { it.readText() } } } }
    Text(text,style=MiuixTheme.textStyles.footnote1)
}
@Composable private fun Hint(text: String) { Text(text,modifier=Modifier.padding(horizontal=14.dp),style=MiuixTheme.textStyles.footnote1,color=MiuixTheme.colorScheme.onSurfaceVariantSummary) }
@Composable private fun Confirm(title: String,message: String,onDismiss: () -> Unit,onConfirm: () -> Unit) {
    val l=LocalLanguage.current
    Dialog(onDismissRequest=onDismiss) { Card { Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text(title,style=MiuixTheme.textStyles.title3); Text(message)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) { Action(l.text("取消","Cancel","取消"),onClick=onDismiss); Action(l.text("确认","Confirm","確認"),onClick=onConfirm) }
    } } }
}
