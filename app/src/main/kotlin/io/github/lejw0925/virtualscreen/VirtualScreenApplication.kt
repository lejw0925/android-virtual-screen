package io.github.lejw0925.virtualscreen

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import io.github.lejw0925.virtualscreen.core.code
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
        try { return block() } finally { mutableRuntimeTasks.update { it - 1 } }
    }
    val mcpWanted: Boolean get() = getSharedPreferences("runtime",MODE_PRIVATE).getBoolean("mcpEnabled",false)
    private fun rememberMcp(enabled: Boolean) { getSharedPreferences("runtime",MODE_PRIVATE).edit().putBoolean("mcpEnabled",enabled).commit() }
    fun setMcpEnabled(enabled: Boolean) {
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
              catch(e: Exception) { rememberMcp(false); mcp.reportStartupError(e.code()) }
            finally { mutableMcpChanging.value = false }
        }
    }
    suspend fun awaitShutdown() { shutdownJob?.join() }
    @Synchronized fun stopRuntime(preserveMcp: Boolean = false) {
        if(!preserveMcp) rememberMcp(false)
        if(shutdownJob?.isActive == true) return
        shutdownJob = runtimeScope.launch { runtimeMutex.withLock {
            runCatching { tunnels.stop() }
            runCatching { mcp.stop() }
            runCatching { sessions.shutdown() }
        } }
    }
    lateinit var activityLog: io.github.lejw0925.virtualscreen.core.ActivityLog
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
        org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
        settings = io.github.lejw0925.virtualscreen.core.AppSettings(this)
        sessions = VirtualScreenSessionManager(this,configuredDisplay={ settings.configForViewer() })
        activityLog = io.github.lejw0925.virtualscreen.core.ActivityLog(this)
        mcp = McpServerController(this, sessions, log=activityLog)
        tunnels = TunnelManager(this, log=activityLog)
        rikka.shizuku.Shizuku.addRequestPermissionResultListener { requestCode, result ->
            if(requestCode == 8732 && result == PackageManager.PERMISSION_GRANTED) runtimeScope.launch {
                runCatching { sessions.ensureAccessibility() }
            }
        }
    }
}
