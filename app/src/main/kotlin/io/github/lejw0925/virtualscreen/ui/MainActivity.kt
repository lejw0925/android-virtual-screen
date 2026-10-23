package io.github.lejw0925.virtualscreen.ui

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.isSystemInDarkTheme
import kotlin.math.roundToInt

/** The launcher is the display viewer. Settings are reached through its floating handle. */
open class MainActivity : ComponentActivity() {
    private val app get()=application as VirtualScreenApplication
    private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var pip by mutableStateOf(false)
    private var openingSettings = false
    private val phoneViewer = java.util.UUID.randomUUID().toString()
    private var previewDismissed = false
    private var remotePreview = false
    private var launchingPreview by mutableStateOf(false)
    private var approvalTarget by mutableStateOf<ApprovalTarget?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        prepareWindowTheme(app)
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        approvalTarget = ApprovalTarget.from(intent)
        pip = isInPictureInPictureMode
        remotePreview = savedInstanceState?.getBoolean("remote_preview") ?: (this is ViewerActivity && intent.getBooleanExtra("pip",false))
        launchingPreview = this is ViewerActivity && intent.getBooleanExtra("pip",false) && !pip && savedInstanceState==null
        intent.removeExtra("pip")
        if(launchingPreview) window.decorView.alpha=0f
        if(BuildConfig.DEBUG && intent.getBooleanExtra("device_test",false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes=window.attributes.apply { preferredRefreshRate=120f }
        WindowCompat.getInsetsController(window,window.decorView).apply {
            show(WindowInsetsCompat.Type.statusBars()); hide(WindowInsetsCompat.Type.navigationBars()); systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { VirtualScreenTheme(app, accessibilityFeedback = !pip && !launchingPreview) {
            val guideDone by app.settings.guideCompleted.collectAsState()
            val approval = approvalTarget
            if (approval != null) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                WindowAppearance(MaterialTheme.colorScheme.surface)
                ApprovalDialog(app, approval) {
                    previewDismissed = true
                    endPhoneSession()
                    ViewerActivity.dismissPreview()
                    finish()
                }
            } else if(launchingPreview && !pip) Box(Modifier.fillMaxSize().background(Color.Transparent)) {
                WindowAppearance(Color.Transparent)
            } else if(!guideDone && this !is ViewerActivity && !pip) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
                WindowAppearance(MaterialTheme.colorScheme.background)
                SetupGuide(app,{ notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) {}
            } else ViewerScreen(app,pip,{ notifications.launch(Manifest.permission.POST_NOTIFICATIONS) },{ enterPreviewPictureInPicture() },{ openSettings() },{ openSettings("MCP") },{ openSettings("BACKEND") },{ openSettings("LOGS") })
        } }
        lifecycleScope.launch { app.sessions.state.map { Triple(it.displayId,it.frame?.width,it.frame?.height) }.distinctUntilChanged().collect { geometry ->
            if(geometry.first==null) endPhoneSession()
            else if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus()) beginPhoneSession()
            if(this@MainActivity is ViewerActivity && geometry.first==null) finish()
            else setPictureInPictureParams(pipParams())
        } }
        lifecycleScope.launch {
            app.sessions.state.map { it.lease?.owner }.distinctUntilChanged().collect { owner ->
                when {
                    owner == VirtualScreenSessionManager.PHONE_CLIENT -> remotePreview = false
                    owner != null -> remotePreview = true
                    remotePreview -> {
                        remotePreview = false
                        if(pip || launchingPreview) dismissPreview()
                    }
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if(app.mcpWanted && !app.mcp.state().running) { foreground(app); app.restoreMcp() }
        if(launchingPreview && !pip) requestPreviewPictureInPicture()
        if(approvalTarget == null && !pip && !launchingPreview && !previewDismissed && app.sessions.state.value.displayId!=null) {
            openingSettings = false
            if(this !is ViewerActivity) ViewerActivity.dismissPreview()
            beginPhoneSession()
        }
        WindowCompat.getInsetsController(window,window.decorView).apply { show(WindowInsetsCompat.Type.statusBars()); hide(WindowInsetsCompat.Type.navigationBars()) }
        setPictureInPictureParams(pipParams())
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean,newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode,newConfig)
        pip=isInPictureInPictureMode
        app.activityLog.runtime("picture_in_picture", if (pip) "entered" else "exited")
        app.sessions.previewVisibility(pip)
        revealPreviewWindow()
        if(pip) {
            endPhoneSession()
        }
        else if(!isFinishing && !previewDismissed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && hasWindowFocus() && app.sessions.state.value.displayId!=null) { beginPhoneSession() }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus && !isInPictureInPictureMode && !launchingPreview && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && app.sessions.state.value.displayId!=null) beginPhoneSession()
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if(approvalTarget == null && !previewDismissed && !pip && !launchingPreview && !openingSettings && app.settings.flow.value.pipEnabled && app.sessions.state.value.displayId!=null) {
            enterPreviewPictureInPicture()
        }
    }
    override fun onStop() {
        super.onStop()
        endPhoneSession()
    }
    override fun onDestroy() { endPhoneSession(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("remote_preview",remotePreview)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Notification taps reuse the launcher task. Publish the parsed target
        // before any preview state can be resumed, then refresh Compose from it.
        setIntent(intent)
        approvalTarget = ApprovalTarget.from(intent)
        previewDismissed = approvalTarget != null
        launchingPreview = false
        window.decorView.alpha = 1f
        val requestPreview=intent.getBooleanExtra("pip",false)
        intent.removeExtra("pip")
        if(requestPreview) remotePreview=true
        if(requestPreview && !pip) {
            launchingPreview=true
            window.decorView.alpha=0f
            window.decorView.postOnAnimation { if(!isFinishing && !pip) requestPreviewPictureInPicture() }
        }
    }
    private fun pipParams(): PictureInPictureParams {
        val state=app.sessions.state.value
        val ratio=(state.frame?.let { it.width.toFloat()/it.height } ?: state.config.width.toFloat()/state.config.height).coerceIn(0.42f,2.38f)
        return PictureInPictureParams.Builder().setAspectRatio(Rational((ratio*1000).roundToInt(),1000))
            .setSourceRectHint(android.graphics.Rect(0,0,window.decorView.width,window.decorView.height)).setAutoEnterEnabled(approvalTarget == null && app.settings.flow.value.pipEnabled && state.displayId!=null && !openingSettings && !previewDismissed).setSeamlessResizeEnabled(true).build()
    }
    fun enterPreviewPictureInPicture(): Boolean = requestPreviewPictureInPicture()
    private fun requestPreviewPictureInPicture(): Boolean {
        if(isFinishing || pip) return pip
        if(app.sessions.state.value.displayId==null || !app.settings.flow.value.pipEnabled) {
            revealPreviewWindow()
            return false
        }
        val entered=runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
        if(entered) return true
        window.decorView.postOnAnimation {
            if(!isFinishing && !pip && launchingPreview) {
                val retried=runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
                if(!retried) revealPreviewWindow()
            }
        }
        return false
    }
    private fun revealPreviewWindow() {
        launchingPreview=false
        window.decorView.alpha=1f
    }
    private fun openSettings(page: String = "HOME") {
        openingSettings = true
        setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        startActivity(Intent(this,SettingsActivity::class.java).putExtra("page",page))
        if(android.os.Build.VERSION.SDK_INT<34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(io.github.lejw0925.virtualscreen.R.anim.avs_slide_in_right,io.github.lejw0925.virtualscreen.R.anim.avs_slide_out_left)
        }
    }
    private fun beginPhoneSession() {
        if(approvalTarget != null || pip || previewDismissed || launchingPreview) return
        remotePreview=false
        app.sessions.beginPhoneSession(phoneViewer)
    }
    private fun endPhoneSession() {
        val generation = app.sessions.state.value.lease?.generation
        app.sessions.endPhoneSession(phoneViewer,
            retainControl = app.mcp.approvals.pending.value.any { it.controllerGeneration == generation })
    }
    internal fun dismissPreview() {
        previewDismissed = true
        if(pip || launchingPreview) app.sessions.previewVisibility(false)
        endPhoneSession()
        finish()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable internal fun VirtualScreenTheme(app: VirtualScreenApplication, accessibilityFeedback: Boolean = true, content: @Composable () -> Unit) {
    val settings by app.settings.flow.collectAsState()
    val useDarkTheme = when(settings.theme) {
        "Light" -> false
        "Dark" -> true
        else -> isSystemInDarkTheme() // MonetSystem or System
    }
    val useDynamicColors = settings.theme == "MonetSystem"
    val baseColorScheme = when {
        useDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if(useDarkTheme) dynamicDarkColorScheme(LocalContext.current)
            else dynamicLightColorScheme(LocalContext.current)
        }
        useDarkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    val colorScheme = baseColorScheme
    val statusColors = if (useDarkTheme) DarkStatusColors else LightStatusColors
    val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val l=when(settings.language) { "zh-Hans" -> UiLanguage.ZH_HANS; "zh-Hant" -> UiLanguage.ZH_HANT; "en" -> UiLanguage.EN; else -> uiLanguage(locale) }
    MaterialExpressiveTheme(colorScheme=colorScheme, motionScheme=MotionScheme.expressive()) {
        CompositionLocalProvider(LocalLanguage provides l, LocalStatusColors provides statusColors, LocalContentColor provides colorScheme.onSurface) {
            Box(Modifier.fillMaxSize()) {
                content()
                if (accessibilityFeedback) AccessibilitySnackbar(app, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
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
    finally { app.sessions.finishPhoneControl(lease.token) }
}
internal suspend fun phoneInput(app: VirtualScreenApplication,operation: BackendOperation) {
    val lease=phoneLease(app)
    try {
        val observation=app.sessions.observe(lease.owner,lease.token).getOrThrow()
        val result=app.sessions.operate(lease.owner,lease.token,observation.values.getValue("observation_id") as String,operation)
        check(result.ok) { result.code ?: "INPUT_FAILED" }
    } finally { app.sessions.finishPhoneControl(lease.token) }
}

@Composable private fun ViewerScreen(app: VirtualScreenApplication,pip: Boolean,notifications: () -> Unit,onPip: () -> Unit,onSettings: () -> Unit,onConnect: () -> Unit,onBackend: () -> Unit,onLogs: () -> Unit) {
    val l=LocalLanguage.current; val state by app.sessions.state.collectAsState(); val settings by app.settings.flow.collectAsState()
    val tool by app.mcp.activity.collectAsState(); val server by app.mcp.flow.collectAsState(); val scope=rememberCoroutineScope(); val lifecycle=LocalLifecycleOwner.current
    val tunnel by app.tunnels.flow.collectAsState()
    val endpoint = remember(server.host, server.port, tunnel.publicAddress) { connectionAddress(server, tunnel) }
    val mcpChanging by app.mcpChanging.collectAsState()
    val accessibilityConnected by ScreenAccessibilityService.connected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toggleMcp=rememberMcpToggle(app,notifications)
    var view by remember { mutableStateOf<LiveDisplayView?>(null) }; var live by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }; var chooseApp by remember { mutableStateOf(false) }; var keyboard by remember { mutableStateOf(false) }
    var viewerSize by remember { mutableStateOf(IntSize.Zero) }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }; var applications by remember { mutableStateOf(emptyList<DiscoveredApplication>()) }
    val task: (suspend () -> Unit) -> Unit={ block -> scope.launch { busy=true; try { withContext(Dispatchers.IO) { app.runtimeWork { app.awaitShutdown(); block() } } } catch(c: CancellationException) { throw c } catch(e: Exception) { error=e.code() } finally { busy=false } } }
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
                app.sessions.ensureAccessibility()
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
    BackHandler(state.displayId!=null || expanded || keyboard) {
        when { keyboard -> keyboard=false; expanded -> expanded=false; else -> localInput(BackendOperation.Back) }
    }
    val imeVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
    val background=if(state.displayId!=null) Color.Black else MaterialTheme.colorScheme.background
    WindowAppearance(background)
    Box(Modifier.fillMaxSize().background(background).windowInsetsPadding(if(pip) WindowInsets(0,0,0,0) else WindowInsets.statusBars.union(WindowInsets.displayCutout)).onSizeChanged { if(!pip && !imeVisible) { viewerSize=it; app.settings.setViewerSize(it.width,it.height) } }) {
        if(state.displayId!=null) AndroidView(factory={ context -> LiveDisplayView(context).also { view=it } },modifier=Modifier.fillMaxSize(),onRelease={ renderer -> renderer.dispose(); if(view===renderer) view=null },update={ renderer ->
            renderer.onLiveChanged={ live=it }
            renderer.onManualDown={
                if(pip || state.errorCode == "NO_RUNNING_APP") null
                else { error=null; app.sessions.beginPhoneGesture().token }
            }
            renderer.onManualComplete={ token -> app.sessions.finishPhoneControl(token) }
            renderer.onCompatibilityInput={ op -> task { phoneInput(app,op) } }
            renderer.onError={ code -> if(code != "DISPLAY_MIRROR_UNAVAILABLE") {
                if (error != code) app.activityLog.runtime("display_preview", "error", code)
                error=code
            } }
            val frame=state.frame
            renderer.setTarget(state.sessionId,app.sessions.liveChannel,LiveGeometry(frame?.width ?: state.config.width,frame?.height ?: state.config.height,state.frameRotation),frame)
        }) else {
            McpHome(
                server = server,
                changing = mcpChanging,
                wanted = app.mcpWanted,
                address = endpoint,
                backend = state.backend,
                backendReady = state.capabilities.details in setOf("SHIZUKU_READY", "ROOT_READY") ||
                    state.capabilities.displayCreation == CapabilityStatus.AVAILABLE,
                accessibilityConnected = accessibilityConnected,
                activityLog = app.activityLog,
                onToggle = toggleMcp,
                onConnect = onConnect,
                onSettings = onSettings,
                onBackend = onBackend,
                onAccessibility = { enabled ->
                    if (enabled) task { app.sessions.ensureAccessibility() }
                    else ScreenAccessibilityService.current?.disableSelf()
                },
                onLogs = onLogs,
                onCopy = { copy(app, app.mcp.clientConfiguration(endpoint)) },
                onCopyAddress = { copy(app, endpoint) },
                error = (server.lastError ?: state.errorCode)?.let { localizedError(l, it) },
            )
        }
        if(state.displayId!=null) FloatingControls(state,server.running,expanded,{ expanded=it },tool?.purpose,tool?.phase,live,pip,settings.pipEnabled,
            onSettings=onSettings,onKeyboard={ expanded=false; keyboard=true },onApps={ task { applications=app.sessions.discoverApplications(includeIcons=true).filter { it.exported && it.packageName!=app.packageName }; chooseApp=true }; expanded=false },
            onPip={ expanded=false; onPip() }, onEnd={
                val session = state.sessionId ?: return@FloatingControls
                expanded=false; keyboard=false; chooseApp=false; error=null
                task {
                    app.sessions.closeFromPhone(session)
                    withContext(Dispatchers.Main) {
                        context.startActivity(Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                }
            })
        if(state.errorCode=="APP_MOVED_TO_PHONE") Card(Modifier.align(Alignment.Center).padding(16.dp)) {
            Text(localizedError(l,"APP_MOVED_TO_PHONE"),modifier=Modifier.padding(16.dp),style=MaterialTheme.typography.bodyMedium)
        }
        if(state.displayId != null && state.errorCode == "NO_RUNNING_APP" && !pip) Card(Modifier.align(Alignment.Center).padding(24.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(l.text("尚未打开应用", "No app is running", "尚未開啟應用程式"), style = MaterialTheme.typography.titleLarge)
                Text(l.text("选择一个应用，在虚拟屏中开始使用。", "Choose an app to open on the virtual display.", "選擇一個應用程式，在虛擬螢幕中開始使用。"))
                Button(onClick = { task {
                    applications=app.sessions.discoverApplications(includeIcons=true).filter { it.exported && it.packageName!=app.packageName }
                    withContext(Dispatchers.Main) { chooseApp=true }
                } }) { Text(l.text("打开应用", "Open an app", "開啟應用程式")) }
            }
        }
        if(error!=null && !pip) androidx.compose.material3.Card(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp).fillMaxWidth()) {
            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) { Text(localizedError(l,error!!),modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium); Action(l.text("关闭","Dismiss","關閉")) { error=null } }
        }
    }
    if(chooseApp) AppPicker(applications,onDismiss={ chooseApp=false }) { item -> chooseApp=false; task {
        phoneControl(app) { lease ->
            val result=app.sessions.launch(lease.owner,lease.token,item.component)
            check(result.ok) { result.code ?: "LAUNCH_FAILED" }; app.settings.update { it.copy(lastComponent=item.component) }
        }
    } }
    if(keyboard) AppDialog(onDismissRequest={ keyboard=false }) {
        var draft by remember { mutableStateOf("") }; var replace by remember { mutableStateOf(false) }
        Text(l.text("输入文字","Type text","輸入文字"),style=MaterialTheme.typography.headlineSmall,color=MaterialTheme.colorScheme.onSurface)
        Field(l.text("文字","Text","文字"),draft,multiline=true) { draft=it }
        SwitchPreference(checked=replace,onCheckedChange={ replace=it },title=l.text("替换全部","Replace all","取代全部"))
        Button(onClick={ localInput(BackendOperation.Text(draft,replace)); keyboard=false },enabled=draft.isNotEmpty() || replace) { Text(l.text("发送到虚拟屏","Send to virtual screen","傳送到虛擬螢幕")) }
    }
}

@Composable private fun AppPicker(apps: List<DiscoveredApplication>,onDismiss: () -> Unit,onChoose: (DiscoveredApplication) -> Unit) {
    val l=LocalLanguage.current
    AppDialog(onDismissRequest=onDismiss) {
        var query by remember { mutableStateOf("") }
        Text(l.text("打开应用","Open an app","開啟應用程式"),style=MaterialTheme.typography.headlineSmall,color=MaterialTheme.colorScheme.onSurface)
        Field(l.text("搜索应用","Search apps","搜尋應用程式"),query) { query=it }
        LazyColumn(Modifier.heightIn(max=420.dp)) { items(apps.filter { it.label.contains(query,true) || it.packageName.contains(query,true) },key={ it.component }) { item ->
            AppIconPreference(title=item.label,icon=item.icon,summary=item.packageName,onClick={ onChoose(item) })
        } }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable private fun FloatingControls(state: SessionState,mcpRunning: Boolean,expanded: Boolean,onExpanded: (Boolean) -> Unit,purpose: String?,phase: String?,live: Boolean,pip: Boolean,pipEnabled: Boolean,
    onSettings: () -> Unit,onKeyboard: () -> Unit,onApps: () -> Unit,onPip: () -> Unit,onEnd: () -> Unit) {
    val l=LocalLanguage.current; val context=LocalContext.current; val host=LocalView.current
    val prefs=remember { context.getSharedPreferences("viewer_controls",0) }
    var right by remember { mutableStateOf(prefs.getBoolean("right",true)) }; var fraction by remember { mutableFloatStateOf(prefs.getFloat("anchor_y",.22f)) }
    var hidden by remember { mutableStateOf(false) }; var drag by remember { mutableStateOf<Offset?>(null) }; var measured by remember { mutableStateOf(IntSize.Zero) }
    val running=phase=="running"
    val accent=when { phase=="failed" -> MaterialTheme.colorScheme.error; running || mcpRunning -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    val status=when { state.lease?.owner == VirtualScreenSessionManager.PHONE_CLIENT -> l.text("手机接管中","Phone control","手機接管中"); running -> l.text("正在执行","Working","正在執行"); state.lease?.owner != null -> l.text("远程控制中","Remote control active","遠端控制中"); mcpRunning -> l.text("MCP 已就绪","MCP ready","MCP 已就緒"); else -> l.text("本地控制","Local control","本機控制") }
    LaunchedEffect(pip) {
        if(pip) {
            hidden=false
            drag=null
            onExpanded(false)
        }
    }
    LaunchedEffect(expanded,drag,hidden,pip) { if(!pip && !expanded && drag==null && !hidden) { delay(4000); hidden=true } }
    DisposableEffect(host) { onDispose { host.systemGestureExclusionRects=emptyList() } }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val density=LocalDensity.current
        val w=with(density) { maxWidth.toPx() }; val h=with(density) { maxHeight.toPx() }
        val targetControlWidth=if(pip) maxOf(0.dp,minOf(maxWidth-20.dp,300.dp)) else if(hidden) 44.dp else minOf(maxWidth-24.dp,if(expanded) 320.dp else 284.dp)
        val animatedControlWidth by animateDpAsState(
            targetValue=targetControlWidth,
            animationSpec=spring(dampingRatio=.78f,stiffness=Spring.StiffnessMediumLow),
            label="floating control width"
        )
        val controlWidth=if(pip) targetControlWidth else animatedControlWidth
        val cw=with(density) { controlWidth.toPx() }
        val ch=if(pip) with(density) { 32.dp.toPx() } else measured.height.takeIf { it>0 }?.toFloat() ?: with(density) { 70.dp.toPx() }
        val top=with(density) { 12.dp.toPx() }
        val bottom=maxOf(WindowInsets.displayCutout.getBottom(density),WindowInsets.navigationBarsIgnoringVisibility.getBottom(density))+with(density) { (if(pip) 10.dp else 12.dp).toPx() }
        val maxY=(h-bottom-ch).coerceAtLeast(top); val x=if(pip) ((w-cw)/2f).coerceAtLeast(0f) else if(right) (w-cw).coerceAtLeast(0f) else 0f
        val y=if(pip) maxY else (h*fraction).coerceIn(top,maxY)
        fun save() { val p=drag ?: return; right=p.x+cw/2>w/2; fraction=if(h>0) (p.y/h).coerceIn(0f,1f) else 0f; prefs.edit().putBoolean("right",right).putFloat("anchor_y",fraction).apply(); drag=null; onExpanded(false); hidden=true }
        val dragModifier=Modifier.pointerInput(w,h,cw,ch,top,maxY) { detectDragGestures(onDragStart={ drag=Offset(x,y) },onDragEnd={ save() },onDragCancel={ save() },onDrag={ change,amount -> change.consume(); val p=drag ?: Offset(x,y); drag=Offset((p.x+amount.x).coerceIn(0f,(w-cw).coerceAtLeast(0f)),(p.y+amount.y).coerceIn(top,maxY)) }) }
        val position=drag ?: Offset(x,y)
        Box(Modifier.offset { IntOffset(position.x.roundToInt(),position.y.roundToInt()) }.width(controlWidth).onSizeChanged { measured=it }.onGloballyPositioned { layout ->
            val p=layout.positionInWindow(); host.systemGestureExclusionRects=listOf(android.graphics.Rect(p.x.roundToInt(),p.y.roundToInt(),(p.x+layout.size.width).roundToInt(),(p.y+layout.size.height).roundToInt()))
        }) {
            if(pip) {
                val pipPurpose=purpose?.takeIf { it.isNotBlank() } ?: status
                Surface(
                    modifier=Modifier.fillMaxWidth().heightIn(min=30.dp).semantics { contentDescription=pipPurpose },
                    shape=RoundedCornerShape(50),
                    color=MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=.94f),
                    tonalElevation=3.dp,
                    shadowElevation=3.dp
                ) {
                    Row(Modifier.padding(horizontal=10.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically) {
                        StatusIndicator(running,accent,Modifier.size(11.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(
                            pipPurpose,
                            modifier=Modifier.weight(1f).basicMarquee(iterations=Int.MAX_VALUE,repeatDelayMillis=700),
                            style=MaterialTheme.typography.labelSmall,
                            maxLines=1,
                            softWrap=false,
                            color=MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else {
                val motion = MaterialTheme.motionScheme
                val foldSpatial = motion.defaultSpatialSpec<Float>()
                val foldEffects = motion.defaultEffectsSpec<Float>()
                val unfoldSpatial = motion.fastSpatialSpec<Float>()
                val unfoldEffects = motion.fastEffectsSpec<Float>()
                AnimatedContent(
                targetState=hidden,
                transitionSpec={
                    (fadeIn(foldEffects)+scaleIn(initialScale=.88f,animationSpec=foldSpatial))
                        .togetherWith(fadeOut(unfoldEffects)+scaleOut(targetScale=.88f,animationSpec=unfoldSpatial))
                },
                label="floating control fold"
            ) { folded ->
                if(folded) Box(Modifier.then(dragModifier).size(48.dp)) {
                    FilledTonalIconButton(
                        onClick={ hidden=false },
                        shapes=IconButtonShapes(MaterialTheme.shapes.large,MaterialTheme.shapes.medium),
                        modifier=Modifier.fillMaxSize().semantics { contentDescription=status }
                    ) {
                        StatusIndicator(running,accent,Modifier.size(22.dp))
                    }
                } else androidx.compose.material3.Card(Modifier.padding(horizontal=8.dp).shadow(8.dp,MaterialTheme.shapes.largeIncreased),shape=MaterialTheme.shapes.largeIncreased) { Column(Modifier.padding(horizontal=12.dp,vertical=6.dp)) {
                    Row(Modifier.fillMaxWidth().then(dragModifier),verticalAlignment=Alignment.CenterVertically) {
                        StatusIndicator(running,accent,Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if(!expanded && running && !purpose.isNullOrBlank()) purpose else status,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,maxLines=if(expanded) 1 else 2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurface)
                        IconButton(onClick={ onExpanded(!expanded) },modifier=Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowForward,if(expanded) l.text("折叠操作","Collapse actions","摺疊操作") else l.text("展开操作","Expand actions","展開操作"),Modifier.size(18.dp).graphicsLayer { rotationZ=if(expanded) -90f else 90f }) }
                    }
                    purpose?.takeIf { expanded && it.isNotBlank() && phase=="running" }?.let { Text(it,modifier=Modifier.padding(top=4.dp),style=MaterialTheme.typography.bodyMedium,maxLines=3,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurface) }
                    AnimatedVisibility(expanded,
                        enter=expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec(),expandFrom=Alignment.Top)+fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                        exit=shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec(),shrinkTowards=Alignment.Top)+fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())) {
                        Column(Modifier.padding(top=10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            val leadingShapes = ButtonGroupDefaults.connectedLeadingButtonShapes()
                            val middleShapes = ButtonGroupDefaults.connectedMiddleButtonShapes()
                            val trailingShapes = ButtonGroupDefaults.connectedTrailingButtonShapes()
                            ButtonGroup(
                                overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) },
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                floatingAction(Icons.Default.Apps, l.text("应用", "Apps", "應用"),
                                    IconButtonShapes(leadingShapes.shape, leadingShapes.pressedShape), onApps)
                                floatingAction(Icons.Default.Edit, l.text("输入", "Type", "輸入"),
                                    IconButtonShapes(middleShapes.shape, middleShapes.pressedShape), onKeyboard)
                                if(pipEnabled) floatingAction(Icons.Default.PictureInPicture, l.text("画中画", "PiP", "子母畫面"),
                                    IconButtonShapes(middleShapes.shape, middleShapes.pressedShape), onPip)
                                floatingAction(Icons.Default.Settings, l.text("设置", "Settings", "設定"),
                                    IconButtonShapes(middleShapes.shape, middleShapes.pressedShape), onSettings)
                                floatingAction(Icons.Default.StopCircle, l.text("结束", "End", "結束"),
                                    IconButtonShapes(trailingShapes.shape, trailingShapes.pressedShape), onEnd)
                            }
                            if(!live) Text(l.text("兼容预览","Compatibility preview","相容預覽"),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } }
            } }
        }
    }
}
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun ButtonGroupScope.floatingAction(
    icon: ImageVector,
    label: String,
    shapes: IconButtonShapes,
    onClick: () -> Unit,
) {
    customItem(
        buttonGroupContent = {
            val interactionSource = remember { MutableInteractionSource() }
            FilledTonalIconButton(
                onClick = onClick,
                modifier = Modifier.weight(1f).height(48.dp).widthIn(min = 48.dp)
                    .animateWidth(interactionSource, 4.dp),
                shapes = shapes,
                interactionSource = interactionSource,
            ) {
                Icon(icon, label, Modifier.size(22.dp))
            }
        },
        menuContent = { menuState ->
            FilledTonalIconButton(
                onClick = { menuState.dismiss(); onClick() },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(icon, label, Modifier.size(22.dp))
            }
        },
    )
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
