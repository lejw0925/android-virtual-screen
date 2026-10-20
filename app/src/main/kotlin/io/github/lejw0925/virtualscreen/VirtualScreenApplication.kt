package io.github.lejw0925.virtualscreen

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import io.github.lejw0925.virtualscreen.core.code
import io.github.lejw0925.virtualscreen.core.ActivityLog
import io.github.lejw0925.virtualscreen.core.ActivityLogEntry
import io.github.lejw0925.virtualscreen.core.RuntimeDiagnostics
import io.github.lejw0925.virtualscreen.core.failure
import io.github.lejw0925.virtualscreen.core.runtime
import io.github.lejw0925.virtualscreen.core.VirtualScreenSessionManager
import io.github.lejw0925.virtualscreen.mcp.McpServerController
import io.github.lejw0925.virtualscreen.tunnel.TunnelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class VirtualScreenApplication : Application() {
    private val runtimeScope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val runtimeMutex = Mutex()
    @Volatile private var shutdownJob: Job? = null
    private var startupJob: Job? = null
    private val mutableMcpChanging = MutableStateFlow(false)
    val mcpChanging = mutableMcpChanging.asStateFlow()
    private val mutableRuntimeTasks = MutableStateFlow(0)
    val runtimeTasks = mutableRuntimeTasks.asStateFlow()
    suspend fun <T> runtimeWork(block: suspend () -> T): T {
        mutableRuntimeTasks.update { it + 1 }
        try { return block() }
        catch (failure: Exception) { activityLog.failure("runtime_task", failure); throw failure }
        finally { mutableRuntimeTasks.update { it - 1 } }
    }
    val mcpWanted: Boolean get() = getSharedPreferences("runtime",MODE_PRIVATE).getBoolean("mcpEnabled",false)
    private fun rememberMcp(enabled: Boolean) { getSharedPreferences("runtime",MODE_PRIVATE).edit().putBoolean("mcpEnabled",enabled).commit() }
    fun setMcpEnabled(enabled: Boolean) {
        activityLog.runtime("mcp_switch", if (enabled) "enable_requested" else "disable_requested")
        rememberMcp(enabled)
        if(enabled) {
            startForegroundService(Intent(this,VirtualScreenForegroundService::class.java))
            restoreMcp()
        } else {
            mutableMcpChanging.value = true
            runtimeScope.launch {
                try { runtimeMutex.withLock { tunnels.stop(); mcp.stop() } }
                finally { mutableMcpChanging.value = false }
            }
        }
    }
    /** Runs in the application scope so navigating away cannot cancel server startup. */
    @Synchronized fun restoreMcp() {
        if(!mcpWanted || startupJob?.isActive == true) return
        mutableMcpChanging.value = true
        startupJob = runtimeScope.launch {
            activityLog.runtime("mcp_restore", "starting", "backend=${settings.flow.value.backend}")
            try {
                awaitShutdown()
                runtimeMutex.withLock {
                    if(!mcpWanted) return@withLock
                    sessions.initializeBackend(settings.flow.value.backend)
                    val capabilities = sessions.ensureAccessibility()
                    check(capabilities.displayCreation != io.github.lejw0925.virtualscreen.backend.CapabilityStatus.REQUIRES_AUTHORIZATION) {
                        if(settings.flow.value.backend == io.github.lejw0925.virtualscreen.backend.BackendKind.ROOT) "ROOT_PERMISSION_REQUIRED" else "SHIZUKU_PERMISSION_REQUIRED"
                    }
                    mcp.start(settings.flow.value.lan).getOrThrow()
                    sessions.clearInactiveDisplayError()
                }
            } catch(c: CancellationException) { throw c }
              catch(e: Exception) { activityLog.failure("mcp_restore", e); rememberMcp(false); mcp.reportStartupError(e.code()) }
            finally { mutableMcpChanging.value = false }
        }
    }
    suspend fun awaitShutdown() { shutdownJob?.join() }
    @Synchronized fun stopRuntime(preserveMcp: Boolean = false) {
        if(!preserveMcp) rememberMcp(false)
        if(shutdownJob?.isActive == true) return
        shutdownJob = runtimeScope.launch { runtimeMutex.withLock {
            activityLog.runtime("runtime", "stopping", "preserve_mcp=$preserveMcp")
            runCatching { tunnels.stop() }.onFailure { activityLog.failure("tunnel_stop", it) }
            runCatching { mcp.stop() }.onFailure { activityLog.failure("mcp_stop", it) }
            runCatching { sessions.shutdown() }.onFailure { activityLog.failure("screen_shutdown", it) }
            activityLog.runtime("runtime", "stopped")
        } }
    }
    lateinit var activityLog: ActivityLog
        private set
    lateinit var sessions: VirtualScreenSessionManager
        private set
    lateinit var mcp: McpServerController
        private set
    lateinit var tunnels: TunnelManager
        private set

    lateinit var settings: io.github.lejw0925.virtualscreen.core.AppSettings
        private set

    override fun onCreate() {
        super.onCreate()
        activityLog = ActivityLog(this)
        RuntimeDiagnostics(this, activityLog).install()
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
        settings = io.github.lejw0925.virtualscreen.core.AppSettings(this)
        sessions = VirtualScreenSessionManager(this,configuredDisplay={ settings.configForViewer() }, log=activityLog)
        mcp = McpServerController(this, sessions, log=activityLog)
        tunnels = TunnelManager(this, log=activityLog)
        rikka.shizuku.Shizuku.addRequestPermissionResultListener { requestCode, result ->
            if (requestCode == 8732) activityLog.runtime("shizuku_permission",
                if (result == PackageManager.PERMISSION_GRANTED) "granted" else "denied")
            if(requestCode == 8732 && result == PackageManager.PERMISSION_GRANTED) runtimeScope.launch {
                runCatching { sessions.ensureAccessibility() }.onFailure { activityLog.failure("accessibility_enable", it) }
            }
        }
    }

    fun diagnosticReport(entries: List<ActivityLogEntry>): String = buildString {
        val screen = sessions.state.value
        val server = mcp.state()
        val tunnel = tunnels.state()
        appendLine("Virtual Screen diagnostic report · ${java.time.Instant.now()}")
        appendLine(RuntimeDiagnostics.environment(this@VirtualScreenApplication))
        appendLine("backend=${screen.backend}; state=${screen.status}; display=${screen.displayId}; error=${screen.errorCode}")
        appendLine("display_config=${screen.config.width}x${screen.config.height}@${screen.config.density}; preview=${screen.previewStatus}")
        appendLine("capabilities: display=${screen.capabilities.displayCreation}; capture=${screen.capabilities.frameCapture}; input=${screen.capabilities.inputInjection}; nodes=${screen.capabilities.accessibilityNodes}")
        appendLine("mcp_wanted=$mcpWanted; mcp_running=${server.running}; mcp_error=${server.lastError}; clients=${server.sessionCount}")
        appendLine("tunnel=${tunnel.mode}; phase=${tunnel.phase}; tunnel_error=${tunnel.errorCode}")
        appendLine("uptime_ms=${android.os.SystemClock.uptimeMillis()}; log_storage_error=${activityLog.writeError.value}")
        appendLine()
        entries.forEach { appendLine(it.text()) }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        activityLog.runtime("memory", if (level == TRIM_MEMORY_UI_HIDDEN) "background" else "warning", "level=$level")
    }
}
