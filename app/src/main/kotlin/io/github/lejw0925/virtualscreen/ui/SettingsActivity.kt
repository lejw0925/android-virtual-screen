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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.lejw0925.virtualscreen.BuildConfig
import io.github.lejw0925.virtualscreen.R
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.VirtualScreenForegroundService
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SettingsPower
import androidx.compose.material.icons.filled.Hub
import io.github.lejw0925.virtualscreen.mcp.McpToolProbe
import io.github.lejw0925.virtualscreen.mcp.McpToolRegistry
import kotlinx.coroutines.*

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
private enum class SettingsPage { HOME,BACKEND,DISPLAY,MCP,TUNNEL,APPEARANCE,BACKGROUND,LOGS,ABOUT,LICENSES,GUIDE }
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable private fun SettingsContent(app: VirtualScreenApplication,initial: String,notifications: () -> Unit,onExit: () -> Unit) {
    val l=LocalLanguage.current
    val background=MaterialTheme.colorScheme.surface
    WindowAppearance(background)
    var stack by rememberSaveable { mutableStateOf(listOf(SettingsPage.HOME).let { pages ->
        val destination=runCatching { SettingsPage.valueOf(initial) }.getOrDefault(SettingsPage.HOME)
        if(destination==SettingsPage.HOME) pages else pages+destination
    }) }
    var forward by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    val task: (suspend () -> Unit) -> Unit={ block -> scope.launch { busy=true; try { withContext(Dispatchers.IO) { app.runtimeWork { app.awaitShutdown(); block() } } } catch(c: CancellationException) { throw c } catch(e: Exception) { error=e.code() } finally { busy=false } } }
    fun go(page: SettingsPage) { forward=true; stack=stack+page }
    fun back() { if(stack.size>1) { forward=false; stack=stack.dropLast(1) } else onExit() }
    BackHandler { back() }
    val motion = MaterialTheme.motionScheme
    val enterSpec = motion.defaultSpatialSpec<IntOffset>()
    val enterFade = motion.defaultEffectsSpec<Float>()
    val exitSpec = motion.fastSpatialSpec<IntOffset>()
    val exitFade = motion.fastEffectsSpec<Float>()
    AnimatedContent(targetState=stack.last(),modifier=Modifier.fillMaxSize().background(background),transitionSpec={
        val direction=if(forward) 1 else -1
        (slideInHorizontally(enterSpec) { it*direction/4 }+fadeIn(enterFade)) togetherWith
            (slideOutHorizontally(exitSpec) { -it*direction/6 }+fadeOut(exitFade))
    },label="settings navigation") { page ->
        val scroll=TopAppBarDefaults.enterAlwaysScrollBehavior()
        Scaffold(modifier=Modifier.nestedScroll(scroll.nestedScrollConnection),containerColor=MaterialTheme.colorScheme.surface,topBar={ TopAppBar(title={ Text(pageTitle(l,page),color=MaterialTheme.colorScheme.onSurface) },colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface,scrolledContainerColor=MaterialTheme.colorScheme.surfaceContainer),scrollBehavior=scroll,
            navigationIcon={ IconButton(onClick={ back() },modifier=Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack,l.text("返回","Back","返回"),modifier=Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurface) } }) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())) {
                Column(
                    Modifier.widthIn(max = currentContentWidth().cap(720.dp)).fillMaxWidth().align(Alignment.TopCenter).padding(horizontal=16.dp).padding(top=12.dp,bottom=32.dp),
                    verticalArrangement=Arrangement.spacedBy(24.dp),
                ) {
                    if(busy && page != SettingsPage.BACKEND && page != SettingsPage.TUNNEL) {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    }
                    error?.let { code ->
                        Surface(
                            Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ) {
                            Row(Modifier.padding(start=16.dp,end=8.dp,top=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text(localizedError(l,code),Modifier.weight(1f))
                                TextButton(onClick={ error=null }) { Text(l.text("关闭","Dismiss","關閉")) }
                            }
                        }
                    }
                    when(page) {
                        SettingsPage.HOME -> SettingsHome(app,::go)
                        SettingsPage.BACKEND -> BackendPreferences(app,task,busy)
                        SettingsPage.DISPLAY -> DisplayPreferences(app,task)
                        SettingsPage.MCP -> McpPreferences(app,task,notifications)
                        SettingsPage.TUNNEL -> TunnelSettings(app,task,notifications,busy)
                        SettingsPage.APPEARANCE -> AppearancePreferences(app)
                        SettingsPage.BACKGROUND -> BackgroundPreferences(app,task)
                        SettingsPage.LOGS -> ActivityLogs(app)
                        SettingsPage.ABOUT -> AboutPreferences(app) { go(SettingsPage.LICENSES) }
                        SettingsPage.LICENSES -> LicenseText(app)
                        SettingsPage.GUIDE -> SetupGuide(app,notifications,scrollable=false) { back() }
                    }
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
    SettingsPage.LOGS -> l.text("运行日志","Activity logs","執行日誌")
    SettingsPage.ABOUT -> l.text("关于","About","關於")
    SettingsPage.LICENSES -> l.text("开源许可与构建来源","Licenses and build sources","開源授權與建置來源")
    SettingsPage.GUIDE -> l.text("使用指南","Getting started","使用指南")
}
@Composable internal fun Section(label: String, content: @Composable () -> Unit) {
    PreferenceGroup(title = label) { content() }
}
@Composable private fun SettingsHome(app: VirtualScreenApplication,go: (SettingsPage) -> Unit) {
    val l=LocalLanguage.current; val s by app.sessions.state.collectAsState(); val server by app.mcp.flow.collectAsState(); val tunnel by app.tunnels.flow.collectAsState()
    Section(l.text("虚拟屏","Virtual screen","虛擬螢幕")) {
        ArrowPreference(title=pageTitle(l,SettingsPage.BACKEND),summary=s.backend.wireName.uppercase(),icon=Icons.Default.Security,onClick={ go(SettingsPage.BACKEND) })
        ArrowPreference(title=pageTitle(l,SettingsPage.DISPLAY),icon=Icons.AutoMirrored.Filled.ScreenShare,onClick={ go(SettingsPage.DISPLAY) })
    }
    Section(l.text("连接","Connections","連線")) {
        ArrowPreference(title=pageTitle(l,SettingsPage.MCP),summary=if(server.running) l.text("运行中 · ${server.sessionCount} 个客户端","Running · ${server.sessionCount} clients","執行中 · ${server.sessionCount} 個用戶端") else l.text("已关闭","Off","已關閉"),icon=Icons.Default.Hub,onClick={ go(SettingsPage.MCP) })
        ArrowPreference(title=pageTitle(l,SettingsPage.TUNNEL),summary=tunnelLabel(l,tunnel.mode)+" · "+phaseLabel(l,tunnel.phase),icon=Icons.Default.SettingsEthernet,onClick={ go(SettingsPage.TUNNEL) })
    }
    Section(l.text("应用","Application","應用程式")) {
        ArrowPreference(title=pageTitle(l,SettingsPage.APPEARANCE),icon=Icons.Default.Palette,onClick={ go(SettingsPage.APPEARANCE) })
        ArrowPreference(title=pageTitle(l,SettingsPage.BACKGROUND),icon=Icons.Default.SettingsPower,onClick={ go(SettingsPage.BACKGROUND) })
        ArrowPreference(title=pageTitle(l,SettingsPage.LOGS),icon=Icons.Default.Description,onClick={ go(SettingsPage.LOGS) })
        ArrowPreference(title=pageTitle(l,SettingsPage.ABOUT),summary=BuildConfig.VERSION_NAME,icon=Icons.Default.Info,onClick={ go(SettingsPage.ABOUT) })
        ArrowPreference(title=pageTitle(l,SettingsPage.GUIDE),icon=Icons.AutoMirrored.Filled.HelpOutline,onClick={ go(SettingsPage.GUIDE) })
    }
}
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BackendPreferences(app: VirtualScreenApplication, task: (suspend () -> Unit) -> Unit, busy: Boolean) {
    val l = LocalLanguage.current
    val state by app.sessions.state.collectAsState()
    val settings by app.settings.flow.collectAsState()
    var confirmProbe by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var probeProgress by remember(state.backend) { mutableIntStateOf(0) }
    val toolCount = remember(app.sessions, state.backend) { McpToolRegistry(app.sessions).tools.size }
    val report = state.toolProbe

    Section(l.text("授权", "Authorization", "授權")) {
        WindowDropdownPreference(
            title = l.text("权限后端", "Backend", "權限後端"),
            items = listOf("Root", "Shizuku / Stellar"),
            selectedIndex = state.backend.ordinal,
            enabled = !busy,
            icon = Icons.Default.Security,
            onSelectedIndexChange = { index -> task {
                val backend = BackendKind.entries[index]
                app.sessions.selectBackend(backend)
                app.settings.update { it.copy(backend = backend) }
                app.sessions.ensureAccessibility()
            } },
        )
        SwitchPreference(
            title = l.text("画中画预览", "Picture-in-picture preview", "子母畫面預覽"),
            summary = l.text("预览虚拟屏画面悬浮窗", "Preview the virtual display in a floating window", "預覽虛擬螢幕畫面浮動視窗"),
            checked = settings.pipEnabled,
            icon = Icons.Default.PictureInPictureAlt,
            onCheckedChange = { value -> app.settings.update { it.copy(pipEnabled = value) } },
        )
        ArrowPreference(
            title = l.text("系统画中画设置", "System PiP settings", "系統子母畫面設定"),
            icon = Icons.Default.PictureInPictureAlt,
            onClick = {
                val intent = Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", android.net.Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(app.packageManager) != null) app.startActivity(intent)
                else app.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )
        LiveUpdatesPreference(app)
    }

    AccessibilityPermission(app, busy) { task { app.sessions.requestAuthorization() } }

    Section(l.text("检测结果", "Probe results", "檢測結果")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when {
                        probing -> l.text("正在检测 · $probeProgress/$toolCount", "Checking · $probeProgress/$toolCount", "正在檢測 · $probeProgress/$toolCount")
                        report != null -> l.text("${report.passedTools}/${report.totalTools} 通过", "${report.passedTools}/${report.totalTools} passed", "${report.passedTools}/${report.totalTools} 通過")
                        else -> l.text("待实际检测", "Not yet checked", "待實際檢測")
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                FilledTonalButton(onClick = { confirmProbe = true }, enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(
                        if (report == null) Icons.Default.PlayArrow else Icons.Default.Refresh,
                        null, Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (report == null) l.text("授权并检测", "Authorize & check", "授權並檢測")
                        else l.text("重新检测", "Check again", "重新檢測"))
                }
            }
            if (probing || busy) {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearWavyProgressIndicator(
                    progress = { if (report == null) 0f else report.passedTools / report.totalTools.toFloat().coerceAtLeast(1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = when {
                        report == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        report.passedTools == report.totalTools -> LocalStatusColors.current.success
                        else -> MaterialTheme.colorScheme.error
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    amplitude = { 1f },
                    waveSpeed = 0.dp,
                )
            }
        }
        val c = state.capabilities
        listOf(
            Triple(l.text("显示创建", "Display creation", "顯示建立"), Icons.AutoMirrored.Filled.ScreenShare, c.displayCreation),
            Triple(l.text("截图", "Capture", "截圖"), Icons.Default.CameraAlt, c.frameCapture),
            Triple(l.text("输入", "Input", "輸入"), Icons.Default.TouchApp, c.inputInjection),
            Triple(l.text("节点", "Nodes", "節點"), Icons.Default.AccountTree, c.accessibilityNodes),
            Triple(l.text("熄屏运行", "Screen-off operation", "熄屏執行"), Icons.Default.Bedtime, c.screenOff),
        ).forEach { (name, icon, value) ->
            val status = if (probing) CapabilityStatus.UNKNOWN else value
            StatusPreference(name, icon, capabilityLabel(l, status), capabilityTone(status))
        }
        if (report != null) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            report.results.forEach { result ->
                val tone = when {
                    probing || result.attempts == 0 -> StatusTone.Neutral
                    result.passed -> StatusTone.Positive
                    else -> StatusTone.Negative
                }
                StatusPreference(
                    title = mcpToolLabel(l, result.tool),
                    icon = mcpToolIcon(result.tool),
                    status = when {
                        probing -> l.text("检测中", "Checking", "檢測中")
                        result.passed -> l.text("通过", "Passed", "通過")
                        result.attempts == 0 -> l.text("未执行", "Skipped", "未執行")
                        else -> l.text("失败", "Failed", "失敗")
                    },
                    tone = tone,
                    summary = if (!probing && !result.passed) result.code?.let { localizedError(l, it) } else null,
                )
            }
        }
    }
    if (confirmProbe) AlertDialog(
        onDismissRequest = { if (!busy) confirmProbe = false },
        icon = { Icon(Icons.AutoMirrored.Filled.FactCheck, null) },
        title = { Text(l.text("运行权限检测", "Run backend check", "執行權限檢測")) },
        text = { Text(l.text("将执行全部 $toolCount 个 MCP 工具，并在临时虚拟屏中验证；检测结束后会关闭并清理。", "All $toolCount MCP tools will run on a temporary virtual display. The display will be closed and cleaned up when the check finishes.", "將執行全部 $toolCount 個 MCP 工具，並在臨時虛擬螢幕中驗證；檢測結束後會關閉並清理。")) },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                confirmProbe = false
                probeProgress = 0
                task {
                    withContext(Dispatchers.Main) { probing = true }
                    try {
                        app.sessions.requestAuthorization()
                        val report = McpToolProbe(app.sessions).run { completed, total, _ ->
                            android.os.Handler(android.os.Looper.getMainLooper()).post { probeProgress = completed.coerceIn(0, total) }
                        }
                        app.sessions.applyToolProbe(report)
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main) { probing = false }
                    }
                }
            }) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(l.text("开始检测", "Run check", "開始檢測"))
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirmProbe = false }) { Text(l.text("取消", "Cancel", "取消")) } },
    )
}

private fun capabilityTone(status: CapabilityStatus) = when (status) {
    CapabilityStatus.AVAILABLE -> StatusTone.Positive
    CapabilityStatus.UNKNOWN -> StatusTone.Neutral
    CapabilityStatus.UNAVAILABLE, CapabilityStatus.REQUIRES_AUTHORIZATION -> StatusTone.Negative
}

private fun mcpToolIcon(tool: String) = when (tool) {
    "acquire_control", "release_control", "request_control" -> Icons.Default.Security
    "create_screen", "configure_screen", "close_screen", "launch_app" -> Icons.AutoMirrored.Filled.ScreenShare
    "observe" -> Icons.Default.CameraAlt
    "tap_at", "long_press_at", "swipe_path", "scroll_at", "input_text", "press_key" -> Icons.Default.TouchApp
    "node_action" -> Icons.Default.AccountTree
    "get_status" -> Icons.Default.Info
    "get_request_receipt" -> Icons.Default.Description
    else -> Icons.Default.Code
}
private fun mcpToolLabel(l: UiLanguage,tool: String)=when(tool) {
    "acquire_control" -> l.text("申请控制权","Acquire control","申請控制權")
    "release_control" -> l.text("释放控制权","Release control","釋放控制權")
    "request_control" -> l.text("请求控制权","Request control","請求控制權")
    "get_status" -> l.text("读取状态","Read status","讀取狀態")
    "get_request_receipt" -> l.text("读取请求回执","Read request receipt","讀取請求回執")
    "create_screen" -> l.text("创建虚拟屏","Create display","建立虛擬螢幕")
    "configure_screen" -> l.text("配置虚拟屏","Configure display","設定虛擬螢幕")
    "close_screen" -> l.text("关闭虚拟屏","Close display","關閉虛擬螢幕")
    "list_apps" -> l.text("列出应用","List apps","列出應用程式")
    "launch_app" -> l.text("启动应用","Launch app","啟動應用程式")
    "observe" -> l.text("观察屏幕","Observe display","觀察螢幕")
    "tap_at" -> l.text("点击","Tap","點按")
    "long_press_at" -> l.text("长按","Long press","長按")
    "swipe_path" -> l.text("滑动","Swipe","滑動")
    "scroll_at" -> l.text("滚动","Scroll","捲動")
    "input_text" -> l.text("输入文本","Input text","輸入文字")
    "node_action" -> l.text("节点操作","Node action","節點操作")
    "press_key" -> l.text("按键","Press key","按鍵")
    "wait" -> l.text("等待条件","Wait","等待條件")
    else -> tool
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
        SwitchPreference(checked=settings.fitViewer,onCheckedChange={ value -> app.settings.update { it.copy(fitViewer=value,matchHostDensity=value) } },title=l.text("贴合手机显示区域","Fit the phone viewport","貼合手機顯示區域"))
        ArrowPreference(title=l.text("分辨率与 DPI","Resolution and DPI","解析度與 DPI"),summary=app.settings.configForViewer().let { "${it.width} × ${it.height} · ${it.density} dpi" },onClick={ edit=true })
        WindowDropdownPreference(items=listOf("0°","90°","180°","270°"),selectedIndex=settings.config.rotation,title=l.text("初始旋转","Initial rotation","初始旋轉"),onSelectedIndexChange={ index -> app.settings.update { it.copy(config=it.config.copy(rotation=index)) } })
        SwitchPreference(checked=settings.restoreApp,onCheckedChange={ value -> app.settings.update { it.copy(restoreApp=value) } },title=l.text("恢复上次应用","Restore last app","恢復上次應用程式"))
        SwitchPreference(checked=settings.autoApproveRestart,onCheckedChange={ value -> app.settings.update { it.copy(autoApproveRestart=value) } },title=l.text("自动审批重启应用","Auto-approve app restarts","自動核准重新啟動應用程式"),summary=l.text("远程客户端请求重启虚拟屏中的应用时自动批准，不再发送审批通知","Approve remote requests to restart apps on the virtual display without an approval notification","遠端用戶端請求重新啟動虛擬螢幕中的應用程式時自動核准，不再傳送審批通知"))
    }
    if(state.displayId!=null) Section(l.text("控制","Control","控制")) {
        ArrowPreference(title=l.text("应用新的显示参数","Apply new display settings","套用新的顯示參數"),onClick={ restart=true })
    }
    if(edit) AppDialog(onDismissRequest={ edit=false }) {
        val effective=app.settings.configForViewer()
        var width by remember { mutableStateOf(effective.width.toString()) }; var height by remember { mutableStateOf(effective.height.toString()) }; var dpi by remember { mutableStateOf(effective.density.toString()) }
        Text(l.text("显示参数","Display settings","顯示參數"),style=MaterialTheme.typography.headlineSmall,color=MaterialTheme.colorScheme.onSurface)
        Field(l.text("宽度","Width","寬度"),width) { width=it }; if(!settings.fitViewer) Field(l.text("高度","Height","高度"),height) { height=it }; Field("DPI",dpi) { dpi=it }
        Button(onClick={ task { val c=settings.config.copy(width=width.toInt(),height=height.toInt(),density=dpi.toInt()); app.settings.update { it.copy(config=c,matchHostDensity=false) }; withContext(Dispatchers.Main) { edit=false } } }) { Text(l.text("保存","Save","儲存")) }
    }
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
    val endpoint = remember(server.host, server.port, tunnel.publicAddress) { connectionAddress(server, tunnel) }
    val toggleMcp=rememberMcpToggle(app,notifications)
    val alwaysAllow by app.mcp.approvals.alwaysAllow.collectAsState()
    var rotate by remember { mutableStateOf(false) }
    Section(l.text("服务","Server","服務")) {
        SwitchPreference(checked=server.running,onCheckedChange=toggleMcp,title=l.text("MCP 服务","MCP server","MCP 服務"),summary=if(server.running) l.text("已就绪 · ${server.sessionCount} 个客户端","Ready · ${server.sessionCount} clients","已就緒 · ${server.sessionCount} 個用戶端") else l.text("已关闭","Off","已關閉"))
        if(alwaysAllow) ArrowPreference(title=l.text("撤销始终允许接管","Revoke always-allow control","撤銷始終允許接管"),onClick={ app.mcp.approvals.setAlwaysAllow(false) })
        SwitchPreference(checked=settings.lan,onCheckedChange={ value -> task { app.settings.update { it.copy(lan=value) }; if(server.running) { app.tunnels.stop(); app.mcp.stop(); app.mcp.start(value).getOrThrow() } } },title=l.text("局域网访问","LAN access","區域網路存取"))
    }
    Section(l.text("客户端配置","Client configuration","用戶端設定")) {
        ArrowPreference(title=l.text("复制客户端配置","Copy client configuration","複製用戶端設定"),onClick={ copy(app,app.mcp.clientConfiguration(endpoint)) })
        ArrowPreference(title=l.text("复制 Token","Copy token","複製 Token"),summary="••••••••",onClick={ copy(app,server.token) })
        ArrowPreference(title=l.text("轮换 Token","Rotate token","輪換 Token"),onClick={ rotate=true })
        if(server.host=="0.0.0.0") lanAddresses(server.port).forEach { address -> ArrowPreference(title=l.text("局域网地址","LAN address","區域網路位址"),summary=address,onClick={ copy(app,address) }) }
    }
    if(server.clients.isNotEmpty()) Section(l.text("已连接客户端","Connected clients","已連線用戶端")) {
        server.clients.forEach { client -> ArrowPreference(title=client.name,onClick={ app.mcp.disconnectSession(client.id) }) }
    }
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
}
@Composable private fun BackgroundPreferences(app: VirtualScreenApplication,task: (suspend () -> Unit) -> Unit) {
    val l=LocalLanguage.current; val settings by app.settings.flow.collectAsState(); var stop by remember { mutableStateOf(false) }
    Section(l.text("生命周期","Lifecycle","生命週期")) {
        val minutes=(listOf(0,5,10,20,30,60,120,240)+settings.config.idleTimeoutMinutes).distinct().sorted()
        WindowDropdownPreference(items=minutes.map { if(it==0) l.text("不自动释放","Do not release automatically","不自動釋放") else l.text("$it 分钟","$it minutes","$it 分鐘") },selectedIndex=minutes.indexOf(settings.config.idleTimeoutMinutes),title=l.text("空闲释放","Idle release","閒置釋放"),onSelectedIndexChange={ i -> app.settings.update { it.copy(config=it.config.copy(idleTimeoutMinutes=minutes[i])) } })
        SwitchPreference(checked=settings.config.allowScreenOff,onCheckedChange={ value -> app.settings.update { it.copy(config=it.config.copy(allowScreenOff=value)) } },title=l.text("尝试熄屏运行","Try screen-off operation","嘗試熄屏執行"))
    }
    Section(l.text("系统设置","System settings","系統設定")) {
        ArrowPreference(title=l.text("电池优化","Battery optimization","電池最佳化"),onClick={ app.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        ArrowPreference(title=l.text("应用后台权限","App background permissions","應用程式背景權限"),onClick={ app.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        ArrowPreference(title=l.text("停止全部服务","Stop all services","停止所有服務"),onClick={ stop=true })
    }
    if(stop) Confirm(l.text("停止全部服务","Stop all services","停止所有服務"),l.text("关闭虚拟屏、MCP 与隧道。","Close the virtual screen, MCP and tunnel.","關閉虛擬螢幕、MCP 與通道。"),onDismiss={ stop=false }) { stop=false; task { app.stopRuntime(); app.awaitShutdown(); app.stopService(Intent(app,VirtualScreenForegroundService::class.java)) } }
}
@Composable private fun AboutPreferences(app: VirtualScreenApplication,onLicenses: () -> Unit) {
    val l=LocalLanguage.current
    val repository = "https://github.com/lejw0925/android-virtual-screen"
    fun open(url: String) {
        app.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    Section(l.text("虚拟屏","Virtual screen","虛擬螢幕")) {
        Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(l.text("让 Agent 在独立屏幕上工作","A separate screen for your agents","讓 Agent 在獨立螢幕上工作"),
                style=MaterialTheme.typography.headlineSmall,color=MaterialTheme.colorScheme.onSurface)
            Text(l.text("通过 MCP 连接，使用 Root 或 Shizuku 控制 Android 虚拟屏。","Connect over MCP and control an Android virtual display with Root or Shizuku.","透過 MCP 連線，使用 Root 或 Shizuku 控制 Android 虛擬螢幕。"),
                style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(l.text("0.1.0 · 19 个 MCP 工具 · 独立显示与手机端接管审批",
                "0.1.0 · 19 MCP tools · isolated display with on-device takeover approval",
                "0.1.0 · 19 個 MCP 工具 · 獨立顯示與手機端接管審批"),
                style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
        }
        ArrowPreference(title=l.text("仓库","Repository","儲存庫"),summary="lejw0925/android-virtual-screen",
            icon=Icons.Default.Code,onClick={ open(repository) })
        ArrowPreference(title=l.text("查看更新","View updates","檢視更新"),
            summary=l.text("在 GitHub Releases 查看版本与更新说明","Browse versions and release notes on GitHub","在 GitHub Releases 檢視版本與更新說明"),
            icon=Icons.Default.SystemUpdate,onClick={ open("$repository/releases") })
        ArrowPreference(title=l.text("许可","Licenses","授權"),summary=l.text("开源许可、第三方组件与构建来源","Open-source licenses, third-party components and build sources","開源授權、第三方元件與建置來源"),
            icon=Icons.Default.Description,onClick=onLicenses)
        ArrowPreference(title=l.text("版本","Version","版本"),
            summary="${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}",
            icon=Icons.Default.Info,onClick={ copy(app,"${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}") })
    }
}
@Composable private fun LicenseText(app: VirtualScreenApplication) {
    val l = LocalLanguage.current
    var text by remember { mutableStateOf("") }
    LaunchedEffect(app) { text=withContext(Dispatchers.IO) { app.assets.list("licenses").orEmpty().joinToString("\n\n") { name -> name.removeSuffix(".txt")+"\n\n"+app.assets.open("licenses/$name").bufferedReader().use { it.readText() } } } }
    Text(text,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurface,modifier=Modifier.padding(16.dp))
}
@Composable private fun Confirm(title: String,message: String,onDismiss: () -> Unit,onConfirm: () -> Unit) {
    val l=LocalLanguage.current
    AlertDialog(
        onDismissRequest=onDismiss,
        title={ Text(title) },
        text={ Text(message) },
        confirmButton={ Button(onClick=onConfirm) { Text(l.text("确认","Confirm","確認")) } },
        dismissButton={ TextButton(onClick=onDismiss) { Text(l.text("取消","Cancel","取消")) } },
    )
}
