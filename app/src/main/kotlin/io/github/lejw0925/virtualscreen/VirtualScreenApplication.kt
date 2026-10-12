package io.github.lejw0925.virtualscreen

import android.app.Application
import io.github.lejw0925.virtualscreen.core.VirtualScreenSessionManager
import io.github.lejw0925.virtualscreen.mcp.McpServerController
import io.github.lejw0925.virtualscreen.tunnel.TunnelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VirtualScreenApplication : Application() {
    private val runtimeScope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val runtimeMutex = Mutex()
    @Volatile private var shutdownJob: Job? = null
    suspend fun awaitShutdown() { shutdownJob?.join() }
    @Synchronized fun stopRuntime() {
        if(shutdownJob?.isActive == true) return
        shutdownJob = runtimeScope.launch { runtimeMutex.withLock {
            runCatching { tunnels.stop() }
            runCatching { mcp.stop() }
            runCatching { sessions.shutdown() }
        } }
    }
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
        mcp = McpServerController(this, sessions)
        tunnels = TunnelManager(this)
    }
}
