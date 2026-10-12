package io.github.lejw0925.virtualscreen.backend

import android.content.Context
import io.github.lejw0925.virtualscreen.core.ScreenAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RootBackend(context: Context) : CommandBackend(context) {
    override val kind = BackendKind.ROOT
    @Volatile override var liveChannel: LiveDisplayChannel? = null
        private set
    private val transport = Mutex()
    private val exchanges = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val writeLock = Any()
    @Volatile private var process: Process? = null
    private var input: BufferedWriter? = null
    private var output: BufferedReader? = null
    private val deadlines = Executors.newSingleThreadScheduledExecutor { Thread(it, "display-deadlines").apply { isDaemon = true } }

    override suspend fun probe(): BackendCapabilities = withContext(Dispatchers.IO) {
        // Noninteractive capability display only. A real probe is initiated by the user's authorize button.
        val granted = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("rootGranted", false)
        BackendCapabilities(
            if (granted) CapabilityStatus.UNKNOWN else CapabilityStatus.REQUIRES_AUTHORIZATION,
            if (granted) CapabilityStatus.UNKNOWN else CapabilityStatus.REQUIRES_AUTHORIZATION,
            if (granted) CapabilityStatus.UNKNOWN else CapabilityStatus.REQUIRES_AUTHORIZATION,
            if (ScreenAccessibilityService.current != null) CapabilityStatus.AVAILABLE else CapabilityStatus.REQUIRES_AUTHORIZATION,
            details = if (granted) "ROOT_READY" else "ROOT_PERMISSION_REQUIRED")
    }

    suspend fun requestPermission(): Boolean = withContext(Dispatchers.IO) {
        val p = runCatching { ProcessBuilder("su", "-c", "id -u").start() }.getOrNull() ?: return@withContext false
        val timeout = deadlines.schedule({ p.destroyForcibly() }, 30, TimeUnit.SECONDS)
        val granted = try { p.inputStream.bufferedReader().readText().trim() == "0" && p.waitFor(1, TimeUnit.SECONDS) && p.exitValue() == 0 }
        finally { timeout.cancel(false); p.destroy() }
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("rootGranted", granted).apply()
        granted
    }

    override suspend fun connect(config: VirtualScreenConfig): JSONObject = withContext(Dispatchers.IO) {
        val command = "CLASSPATH=${quote(context.applicationInfo.sourceDir)} app_process /system/bin ${RootDisplayCommandMain::class.java.name}"
        val child = ProcessBuilder("su", "-c", command).start()
        process = child; input = child.outputStream.bufferedWriter(); output = child.inputStream.bufferedReader()
        Thread { runCatching { child.errorStream.use { it.copyTo(java.io.OutputStream.nullOutputStream()) } } }.apply { isDaemon = true; start() }
        val nonce=java.util.UUID.randomUUID().toString()
        val pending=RootBridgeProvider.expect(nonce)
        try {
            val result=transact(configPacket(config).put("bridgeNonce",nonce).put("ownerUid",android.os.Process.myUid()))
            if(result.optBoolean("ok")) runCatching { kotlinx.coroutines.withTimeout(2000) { pending.await() } }.getOrNull()?.let { liveChannel=LiveDisplayChannel(result.getString("handleId"),it) }
            result
        } finally { RootBridgeProvider.forget(nonce) }
    }

    override suspend fun transact(request: JSONObject): JSONObject = transport.withLock {
        // Once written, drain the matching reply even when a viewer is stopped.
        // Keep the transport ordered without destroying its Root display.
        val exchange = exchanges.async {
            val p = process ?: error("DISPLAY_GONE")
            val timeout = deadlines.schedule({ p.destroyForcibly() }, 15, TimeUnit.SECONDS)
            try {
                synchronized(writeLock) { val w = input ?: error("DISPLAY_GONE"); w.write(request.toString()); w.newLine(); w.flush() }
                val reader = output ?: error("DISPLAY_GONE")
                while (true) {
                    if (reader.ready()) {
                        val line = reader.readLine() ?: error("DISPLAY_GONE")
                        if (line.startsWith("VIRTUAL_SCREEN_RESULT:")) return@async JSONObject(line.removePrefix("VIRTUAL_SCREEN_RESULT:"))
                    } else { check(p.isAlive) { "DISPLAY_GONE" }; Thread.sleep(5) }
                }
                @Suppress("UNREACHABLE_CODE") error("DISPLAY_GONE")
            } catch (failure: Exception) { p.destroy(); throw failure }
            finally { timeout.cancel(false) }
        }
        try { exchange.await() }
        catch(cancelled: CancellationException) {
            cancelActive()
            withContext(NonCancellable) { runCatching { exchange.await() } }
            throw cancelled
        }
    }

    override fun cancelActive() {
        liveChannel?.let { if(runCatching { it.cancel() }.isSuccess) return }
        runCatching { synchronized(writeLock) { input?.let { w -> w.write(JSONObject().put("action", "cancel").toString()); w.newLine(); w.flush() } } }
    }
    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        liveChannel=null
        val child = process
        // Closing stdin lets app_process release its display before its su wrapper exits.
        synchronized(writeLock) { runCatching { input?.close() }; input = null }
        runCatching { if(child != null && !child.waitFor(1500,TimeUnit.MILLISECONDS)) { child.destroy(); if(!child.waitFor(500,TimeUnit.MILLISECONDS)) child.destroyForcibly() } }
        runCatching { output?.close() }; output = null; process = null
        Unit
    }
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
