package io.github.lejw0925.virtualscreen.ui

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import io.github.lejw0925.virtualscreen.BuildConfig
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.VirtualScreenForegroundService
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.*
import kotlin.math.roundToInt

/** The launcher is the display viewer. Settings are reached through its floating handle. */
open class MainActivity : ComponentActivity() {
    private val app get()=application as VirtualScreenApplication
    private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var pip by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        if(BuildConfig.DEBUG && intent.getBooleanExtra("device_test",false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes=window.attributes.apply { preferredRefreshRate=120f }
        WindowCompat.getInsetsController(window,window.decorView).apply {
            show(WindowInsetsCompat.Type.statusBars()); hide(WindowInsetsCompat.Type.navigationBars()); systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { VirtualScreenTheme(app) {
            val guideDone by app.settings.guideCompleted.collectAsState()
            if(!guideDone && this !is ViewerActivity && !pip) Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).statusBarsPadding()) {
                SetupGuide(app,{ notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) {}
            } else ViewerScreen(app,pip,{ notifications.launch(Manifest.permission.POST_NOTIFICATIONS) },{ enterPreviewPictureInPicture() },{ openSettings() },{ openSettings("MCP") })
        } }
        if(intent.getBooleanExtra("pip",false)) window.decorView.postDelayed({ enterPreviewPictureInPicture() },500)
        lifecycleScope.launch { app.sessions.state.map { Triple(it.displayId,it.frame?.width,it.frame?.height) }.distinctUntilChanged().collect { geometry ->
            if(this@MainActivity is ViewerActivity && geometry.first==null) finish()
            else setPictureInPictureParams(pipParams())
        } }
    }
    override fun onResume() {
        super.onResume()
        if(this !is ViewerActivity && app.sessions.state.value.displayId!=null) ViewerActivity.dismissPreview()
        WindowCompat.getInsetsController(window,window.decorView).apply { show(WindowInsetsCompat.Type.statusBars()); hide(WindowInsetsCompat.Type.navigationBars()) }
        setPictureInPictureParams(pipParams())
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean,newConfig: android.content.res.Configuration) { super.onPictureInPictureModeChanged(isInPictureInPictureMode,newConfig); pip=isInPictureInPictureMode; app.sessions.previewVisibility(pip) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if(intent.getBooleanExtra("pip",false)) window.decorView.post { enterPreviewPictureInPicture() } }
    private fun pipParams(): PictureInPictureParams {
        val state=app.sessions.state.value
        val ratio=(state.frame?.let { it.width.toFloat()/it.height } ?: state.config.width.toFloat()/state.config.height).coerceIn(0.42f,2.38f)
        return PictureInPictureParams.Builder().setAspectRatio(Rational((ratio*1000).roundToInt(),1000))
            .setSourceRectHint(android.graphics.Rect(0,0,window.decorView.width,window.decorView.height)).setAutoEnterEnabled(state.displayId!=null).setSeamlessResizeEnabled(true).build()
    }
    fun enterPreviewPictureInPicture() { if(app.sessions.state.value.displayId!=null) enterPictureInPictureMode(pipParams()) }
    private fun openSettings(page: String = "HOME") {
        setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        startActivity(Intent(this,SettingsActivity::class.java).putExtra("page",page))
        if(android.os.Build.VERSION.SDK_INT<34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(io.github.lejw0925.virtualscreen.R.anim.avs_slide_in_right,io.github.lejw0925.virtualscreen.R.anim.avs_slide_out_left)
        }
    }
}

@Composable internal fun VirtualScreenTheme(app: VirtualScreenApplication,content: @Composable () -> Unit) {
    val settings by app.settings.flow.collectAsState()
    val mode=runCatching { ColorSchemeMode.valueOf(settings.theme) }.getOrDefault(ColorSchemeMode.MonetSystem)
    val controller=remember(mode) { ThemeController(colorSchemeMode=mode) }
    val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val l=when(settings.language) { "zh-Hans" -> UiLanguage.ZH_HANS; "zh-Hant" -> UiLanguage.ZH_HANT; "en" -> UiLanguage.EN; else -> uiLanguage(locale) }
    MiuixTheme(controller) { CompositionLocalProvider(LocalLanguage provides l,content=content) }
}
internal fun foreground(app: VirtualScreenApplication) { app.startForegroundService(Intent(app,VirtualScreenForegroundService::class.java)) }
internal fun phoneLease(app: VirtualScreenApplication): Lease {
    val current=app.sessions.leaseStatus()
    check(current==null || current.owner==VirtualScreenSessionManager.PHONE_CLIENT) { "TAKEOVER_REQUIRED" }
    return (app.sessions.acquireLease(VirtualScreenSessionManager.PHONE_CLIENT) as? LeaseDecision.Granted)?.lease ?: error("TAKEOVER_REQUIRED")
}
internal suspend fun <T> phoneControl(app: VirtualScreenApplication,block: suspend (Lease) -> T): T {
    val lease=app.sessions.phoneTakeover()
    try { return block(lease) }
    finally { if(app.mcp.state().running) app.sessions.finishPhoneControl(lease.token) }
}
internal suspend fun phoneInput(app: VirtualScreenApplication,operation: BackendOperation) {
    val lease=phoneLease(app)
    try {
        val observation=app.sessions.observe(lease.owner,lease.token).getOrThrow()
        val result=app.sessions.operate(lease.owner,lease.token,observation.values.getValue("observation_id") as String,operation)
        check(result.ok) { result.code ?: "INPUT_FAILED" }
    } finally { if(app.mcp.state().running) app.sessions.finishPhoneControl(lease.token) }
}

@Composable private fun ViewerScreen(app: VirtualScreenApplication,pip: Boolean,notifications: () -> Unit,onPip: () -> Unit,onSettings: () -> Unit,onConnect: () -> Unit) {
    val l=LocalLanguage.current; val state by app.sessions.state.collectAsState(); val settings by app.settings.flow.collectAsState()
    val tool by app.mcp.activity.collectAsState(); val server by app.mcp.flow.collectAsState(); val scope=rememberCoroutineScope(); val lifecycle=LocalLifecycleOwner.current
    var view by remember { mutableStateOf<LiveDisplayView?>(null) }; var live by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }; var chooseApp by remember { mutableStateOf(false) }; var keyboard by remember { mutableStateOf(false) }
    var viewerSize by remember { mutableStateOf(IntSize.Zero) }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }; var applications by remember { mutableStateOf(emptyList<DiscoveredApplication>()) }
    val task: (suspend () -> Unit) -> Unit={ block -> scope.launch { busy=true; try { withContext(Dispatchers.IO) { app.awaitShutdown(); block() } } catch(c: CancellationException) { throw c } catch(e: Exception) { error=e.code() } finally { busy=false } } }
    fun localInput(op: BackendOperation) { task { app.sessions.phoneTakeover(); phoneInput(app,op) } }
    LaunchedEffect(app) {
        withContext(Dispatchers.IO) {
            app.awaitShutdown()
            if(app.sessions.state.value.displayId==null) {
                if(!app.settings.backendConfigured) {
                    val shizuku=app.sessions.selectBackend(BackendKind.SHIZUKU)
                    if(shizuku.displayCreation != CapabilityStatus.REQUIRES_AUTHORIZATION) app.settings.update { it.copy(backend=BackendKind.SHIZUKU) }
                }
                app.sessions.initializeBackend(app.settings.flow.value.backend)
            }
        }
    }
    LaunchedEffect(lifecycle,view) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            view?.setResumed(true)
            try { while(isActive) { withContext(Dispatchers.IO) { runCatching { if(app.sessions.state.value.displayId!=null) app.sessions.preview() } }; delay(if(live) 1000 else 350) } }
            finally { view?.setResumed(false) }
        }
    }
    DisposableEffect(Unit) { onDispose { view?.dispose() } }
    BackHandler(state.displayId!=null || expanded || keyboard) {
        when { keyboard -> keyboard=false; expanded -> expanded=false; else -> localInput(BackendOperation.Back) }
    }
    val host=LocalView.current
    val imeVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
    val lightBars=state.displayId==null && MiuixTheme.colorScheme.background.luminance()>.5f
    SideEffect { (host.context as? ComponentActivity)?.let { activity -> WindowCompat.getInsetsController(activity.window,host).isAppearanceLightStatusBars=lightBars } }
    Box(Modifier.fillMaxSize().background(if(state.displayId!=null) Color.Black else MiuixTheme.colorScheme.background).windowInsetsPadding(if(pip) WindowInsets(0,0,0,0) else WindowInsets.statusBars.union(WindowInsets.displayCutout)).onSizeChanged { if(!pip && !imeVisible) { viewerSize=it; app.settings.setViewerSize(it.width,it.height) } }) {
        if(state.displayId!=null) AndroidView(factory={ context -> LiveDisplayView(context).also { view=it } },modifier=Modifier.fillMaxSize(),update={ renderer ->
            renderer.onLiveChanged={ live=it }
            renderer.onManualDown={ if(pip) null else app.sessions.phoneTakeover().token }
            renderer.onManualComplete={ token -> scope.launch(Dispatchers.IO) { if(app.mcp.state().running) app.sessions.finishPhoneControl(token) } }
            renderer.onCompatibilityInput={ op -> task { phoneInput(app,op) } }
            renderer.onError={ code -> if(code != "DISPLAY_MIRROR_UNAVAILABLE") error=code }
            val frame=state.frame
            renderer.setTarget(state.sessionId,app.sessions.liveChannel,LiveGeometry(frame?.width ?: state.config.width,frame?.height ?: state.config.height,state.frameRotation),frame)
        }) else {
            val accent=MiuixTheme.colorScheme.primary
            Column(Modifier.align(Alignment.Center).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=36.dp,vertical=24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp)) {
                ScreenMark(Modifier.size(if(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp<500) 80.dp else 148.dp))
                Text(l.text("虚拟屏","Virtual Screen","虛擬屏"),style=MiuixTheme.textStyles.title2,fontWeight=FontWeight.SemiBold)
                Text(l.text("Agent 的独立屏幕，你的手机照常使用。","A separate screen for your agent. Your phone stays yours.","Agent 的獨立螢幕，你的手機照常使用。"),style=MiuixTheme.textStyles.body2,color=MiuixTheme.colorScheme.onSurfaceVariantSummary,textAlign=TextAlign.Center)
                Text(if(server.running) l.text("连接已就绪 · 等待 Agent 创建屏幕","Connection ready · Waiting for your agent","連線已就緒 · 等待 Agent 建立螢幕") else l.text("创建屏幕后自动画中画，随时接管","Automatic PiP when a display starts. Take over anytime.","建立螢幕後自動子母畫面，隨時接管"),style=MiuixTheme.textStyles.footnote1,color=accent,textAlign=TextAlign.Center)
                Button(onClick=onConnect,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColorsPrimary()) { Text(if(server.running) l.text("连接设置","Connection settings","連線設定") else l.text("连接 Agent","Connect an agent","連線 Agent")) }
                Button(onClick={ notifications(); foreground(app); task {
                    phoneControl(app) { lease ->
                        val result=app.sessions.create(lease.owner,lease.token,app.settings.configForViewer(viewerSize.width,viewerSize.height))
                        check(result.ok) { result.code ?: "CREATE_FAILED" }
                        if(settings.restoreApp && settings.lastComponent.isNotBlank()) app.sessions.launch(lease.owner,lease.token,settings.lastComponent)
                    }
                } },enabled=!busy) { Text(l.text("手动预览","Manual preview","手動預覽")) }
                Action(l.text("权限与设置","Permissions and settings","權限與設定"),onClick=onSettings)
                state.errorCode?.let { Text(localizedError(l,it),style=MiuixTheme.textStyles.footnote1,color=MiuixTheme.colorScheme.error) }
            }
        }
        if(!pip && state.displayId!=null) FloatingControls(state,server.running,expanded,{ expanded=it },tool?.purpose,tool?.phase,live,
            onSettings=onSettings,onKeyboard={ expanded=false; keyboard=true },onApps={ task { applications=app.sessions.discoverApplications().filter { it.exported && it.packageName!=app.packageName }; chooseApp=true }; expanded=false },
            onPip={ expanded=false; onPip() })
        if(error!=null && !pip) Card(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp).fillMaxWidth()) {
            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) { Text(localizedError(l,error!!),modifier=Modifier.weight(1f),style=MiuixTheme.textStyles.body2); Action(l.text("关闭","Dismiss","關閉")) { error=null } }
        }
    }
    if(chooseApp) AppPicker(applications,onDismiss={ chooseApp=false }) { item -> chooseApp=false; task {
        phoneControl(app) { lease ->
            val result=app.sessions.launch(lease.owner,lease.token,item.component)
            if(result.code != "APP_RESTART_CONFIRMATION_REQUIRED") { check(result.ok) { result.code ?: "LAUNCH_FAILED" }; app.settings.update { it.copy(lastComponent=item.component) } }
        }
    } }
    if(keyboard) Dialog(onDismissRequest={ keyboard=false }) { Card { Column(Modifier.padding(22.dp).imePadding(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        var draft by remember { mutableStateOf("") }; var replace by remember { mutableStateOf(false) }
        Text(l.text("输入文字","Type text","輸入文字"),style=MiuixTheme.textStyles.title3)
        Field(l.text("支持中文和 Unicode","Chinese and Unicode supported","支援中文和 Unicode"),draft,multiline=true) { draft=it }
        top.yukonga.miuix.kmp.preference.SwitchPreference(checked=replace,onCheckedChange={ replace=it },title=l.text("替换全部","Replace all","取代全部"))
        Button(onClick={ localInput(BackendOperation.Text(draft,replace)); keyboard=false },enabled=draft.isNotEmpty() || replace) { Text(l.text("发送到虚拟屏","Send to virtual screen","傳送到虛擬螢幕")) }
    } } }
    state.pendingRestart?.let { component -> Dialog(onDismissRequest={ app.sessions.dismissRestart() }) { Card { Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(l.text("应用需要重启","Restart required","應用程式需要重新啟動"),style=MiuixTheme.textStyles.title3)
        Text(l.text("该应用正在其他屏幕运行。重启将结束当前任务并在虚拟屏打开。","This app is running on another display. Restarting ends that task and opens it here.","此應用程式正在其他螢幕執行。重新啟動將結束目前工作並在虛擬螢幕開啟。"))
        Button(onClick={ app.sessions.dismissRestart(); task { phoneControl(app) { lease -> val result=app.sessions.launch(lease.owner,lease.token,component,restartApproved=true); check(result.ok) { result.code ?: "LAUNCH_FAILED" }; app.settings.update { it.copy(lastComponent=component) } } } }) { Text(l.text("接管并重启","Take over and restart","接管並重新啟動")) }
        Action(l.text("取消","Cancel","取消")) { app.sessions.dismissRestart() }
    } } } }
}

@Composable private fun AppPicker(apps: List<DiscoveredApplication>,onDismiss: () -> Unit,onChoose: (DiscoveredApplication) -> Unit) {
    val l=LocalLanguage.current
    Dialog(onDismissRequest=onDismiss) { Card { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        var query by remember { mutableStateOf("") }
        Text(l.text("打开应用","Open an app","開啟應用程式"),style=MiuixTheme.textStyles.title3)
        Field(l.text("搜索应用","Search apps","搜尋應用程式"),query) { query=it }
        LazyColumn(Modifier.heightIn(max=420.dp)) { items(apps.filter { it.label.contains(query,true) || it.packageName.contains(query,true) },key={ it.component }) { item ->
            top.yukonga.miuix.kmp.preference.ArrowPreference(title=item.label,summary=item.packageName,onClick={ onChoose(item) })
        } }
    } } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun FloatingControls(state: SessionState,mcpRunning: Boolean,expanded: Boolean,onExpanded: (Boolean) -> Unit,purpose: String?,phase: String?,live: Boolean,
    onSettings: () -> Unit,onKeyboard: () -> Unit,onApps: () -> Unit,onPip: () -> Unit) {
    val l=LocalLanguage.current; val context=LocalContext.current; val host=LocalView.current
    val prefs=remember { context.getSharedPreferences("viewer_controls",0) }
    var right by remember { mutableStateOf(prefs.getBoolean("right",true)) }; var fraction by remember { mutableFloatStateOf(prefs.getFloat("anchor_y",.22f)) }
    var hidden by remember { mutableStateOf(false) }; var drag by remember { mutableStateOf<Offset?>(null) }; var measured by remember { mutableStateOf(IntSize.Zero) }
    val running=phase=="running"
    val accent=when { phase=="failed" -> MiuixTheme.colorScheme.error; running -> MiuixTheme.colorScheme.primary; else -> MiuixTheme.colorScheme.onSurfaceVariantSummary }
    val status=when { running -> l.text("正在执行","Working","正在執行"); state.lease?.owner != null && state.lease.owner != VirtualScreenSessionManager.PHONE_CLIENT -> l.text("远程控制中","Remote control active","遠端控制中"); mcpRunning -> l.text("MCP 已就绪","MCP ready","MCP 已就緒"); else -> l.text("本地控制","Local control","本機控制") }
    LaunchedEffect(expanded,drag,hidden) { if(!expanded && drag==null && !hidden) { delay(4000); hidden=true } }
    DisposableEffect(host) { onDispose { host.systemGestureExclusionRects=emptyList() } }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val density=LocalDensity.current
        val w=with(density) { maxWidth.toPx() }; val h=with(density) { maxHeight.toPx() }
        val controlWidth=if(hidden) 44.dp else minOf(maxWidth-24.dp,284.dp)
        val cw=with(density) { controlWidth.toPx() }; val ch=measured.height.takeIf { it>0 }?.toFloat() ?: with(density) { 70.dp.toPx() }
        val top=with(density) { 12.dp.toPx() }
        val bottom=maxOf(WindowInsets.displayCutout.getBottom(density),WindowInsets.navigationBarsIgnoringVisibility.getBottom(density))+with(density) { 12.dp.toPx() }
        val maxY=(h-bottom-ch).coerceAtLeast(top); val x=if(right) (w-cw).coerceAtLeast(0f) else 0f
        val y=(h*fraction).coerceIn(top,maxY)
        fun save() { val p=drag ?: return; right=p.x+cw/2>w/2; fraction=if(h>0) (p.y/h).coerceIn(0f,1f) else 0f; prefs.edit().putBoolean("right",right).putFloat("anchor_y",fraction).apply(); drag=null; onExpanded(false); hidden=true }
        val dragModifier=Modifier.pointerInput(w,h,cw,ch,top,maxY) { detectDragGestures(onDragStart={ drag=Offset(x,y) },onDragEnd={ save() },onDragCancel={ save() },onDrag={ change,amount -> change.consume(); val p=drag ?: Offset(x,y); drag=Offset((p.x+amount.x).coerceIn(0f,(w-cw).coerceAtLeast(0f)),(p.y+amount.y).coerceIn(top,maxY)) }) }
        val position=drag ?: Offset(x,y)
        Box(Modifier.offset { IntOffset(position.x.roundToInt(),position.y.roundToInt()) }.width(controlWidth).onSizeChanged { measured=it }.onGloballyPositioned { layout ->
            val p=layout.positionInWindow(); host.systemGestureExclusionRects=listOf(android.graphics.Rect(p.x.roundToInt(),p.y.roundToInt(),(p.x+layout.size.width).roundToInt(),(p.y+layout.size.height).roundToInt()))
        }) {
            if(hidden) Card(Modifier.then(dragModifier),cornerRadius=18.dp) {
                IconButton(onClick={ hidden=false },modifier=Modifier.size(44.dp)) { Icon(MiuixIcons.MoreCircle,l.text("打开悬浮控制","Open floating controls","開啟懸浮控制"),Modifier.size(22.dp)) }
            } else Card(Modifier.padding(horizontal=8.dp).shadow(8.dp,RoundedCornerShape(24.dp)),cornerRadius=24.dp) { Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth().then(dragModifier),verticalAlignment=Alignment.CenterVertically) {
                    StatusIndicator(running,accent,Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(status,modifier=Modifier.weight(1f),style=MiuixTheme.textStyles.body2,maxLines=1,overflow=TextOverflow.Ellipsis)
                    IconButton(onClick={ onExpanded(!expanded) },modifier=Modifier.size(48.dp)) { Icon(MiuixIcons.ChevronForward,if(expanded) l.text("折叠操作","Collapse actions","摺疊操作") else l.text("展开操作","Expand actions","展開操作"),Modifier.size(20.dp).graphicsLayer { rotationZ=if(expanded) -90f else 90f }) }
                }
                purpose?.takeIf { it.isNotBlank() && phase=="running" }?.let { Text(it,modifier=Modifier.padding(top=10.dp),style=MiuixTheme.textStyles.body2,maxLines=3,overflow=TextOverflow.Ellipsis) }
                AnimatedVisibility(expanded,
                    enter=expandVertically(tween(160),expandFrom=Alignment.Top)+fadeIn(tween(100)),
                    exit=shrinkVertically(tween(140),shrinkTowards=Alignment.Top)+fadeOut(tween(90))) {
                    Column(Modifier.padding(top=10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            FloatingAction(MiuixIcons.GridView,l.text("应用","Apps","應用"),onApps)
                            FloatingAction(MiuixIcons.Edit,l.text("输入","Type","輸入"),onKeyboard)
                            FloatingAction(MiuixIcons.ScreenMirroring,l.text("画中画","PiP","子母畫面"),onPip)
                            FloatingAction(MiuixIcons.Settings,l.text("设置","Settings","設定"),onSettings)
                        }
                        if(!live) Text(l.text("兼容预览","Compatibility preview","相容預覽"),style=MiuixTheme.textStyles.footnote1,color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            } }
        }
    }
}
@Composable private fun FloatingAction(icon: ImageVector,label: String,onClick: () -> Unit) {
    Column(horizontalAlignment=Alignment.CenterHorizontally) { IconButton(onClick=onClick,modifier=Modifier.size(48.dp)) { Icon(icon,label,Modifier.size(22.dp)) }; Text(label,style=MiuixTheme.textStyles.footnote2) }
}

/** Matches ETA's quiet status dot and rotating arc during a device operation. */
@Composable private fun StatusIndicator(running: Boolean,accent: Color,modifier: Modifier = Modifier) {
    if(!running) {
        Canvas(modifier) { drawCircle(accent,radius=size.minDimension*.3f) }
        return
    }
    val transition=rememberInfiniteTransition(label="operation indicator")
    val rotation by transition.animateFloat(0f,360f,infiniteRepeatable(tween(1100,easing=LinearEasing)),label="indicator rotation")
    val opacity by transition.animateFloat(.55f,1f,infiniteRepeatable(tween(900),RepeatMode.Reverse),label="indicator pulse")
    Canvas(modifier.graphicsLayer { rotationZ=rotation }) {
        val radius=size.minDimension/2
        drawCircle(accent.copy(alpha=.18f),radius)
        drawArc(Brush.sweepGradient(listOf(Color.Transparent,accent),center),0f,300f,false,style=Stroke(radius*.34f,cap=StrokeCap.Round))
        drawCircle(accent.copy(alpha=opacity),radius*.26f)
    }
}
