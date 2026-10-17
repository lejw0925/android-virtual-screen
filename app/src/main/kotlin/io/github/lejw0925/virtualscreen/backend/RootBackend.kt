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

    /**
     * Magisk-style su accepts `su -c command`, while the AOSP userdebug su
     * shipped by the emulator accepts `su 0 command`. Probe once and keep the
     * working form for the lifetime of this backend.
     */
    @Volatile private var suMode: SuCommandMode? = null

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
        val granted = runCatching {
            if (suMode == null) {
                detectSuMode()
                true
            } else {
                probeUid(startRoot("id -u"))
            }
        }.getOrDefault(false)
        if (!granted) suMode = null
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("rootGranted", granted).apply()
        granted
    }

    suspend fun enableAccessibility(rebind: Boolean = false) = withContext(Dispatchers.IO) {
        val command = "CLASSPATH=${quote(context.applicationInfo.sourceDir)} app_process /system/bin ${AccessibilityAuthorization::class.java.name} ${android.os.Process.myUid()/100000} $rebind"
        val child = startRoot(command, redirectErrorStream = true)
        val timeout = deadlines.schedule({ child.destroyForcibly() }, 15, TimeUnit.SECONDS)
        try {
            val result = child.inputStream.bufferedReader().readText()
            check(child.waitFor(1, TimeUnit.SECONDS) && child.exitValue() == 0 && "ACCESSIBILITY_ENABLED" in result) {
                "ACCESSIBILITY_AUTHORIZATION_FAILED"
            }
        } finally { timeout.cancel(false); child.destroy() }
    }

    override suspend fun connect(config: VirtualScreenConfig): JSONObject = withContext(Dispatchers.IO) {
        val command = "CLASSPATH=${quote(context.applicationInfo.sourceDir)} app_process /system/bin ${RootDisplayCommandMain::class.java.name}"
        val child = startRoot(command)
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

    private fun startRoot(command: String, redirectErrorStream: Boolean = false): Process {
        val mode = suMode ?: detectSuMode()
        val args = mode.arguments(command)
        return ProcessBuilder(*args).apply { redirectErrorStream(redirectErrorStream) }.start()
    }

    private fun detectSuMode(): SuCommandMode {
        synchronized(this) {
            suMode?.let { return it }
            val detected = detectSuCommandMode()
            suMode = detected
            return detected
        }
    }
}

internal fun probeUid(process: Process, timeoutMillis: Long = 30_000, expectedUid: String = "0"): Boolean {
    try {
        // id emits only a few bytes. Wait before reading so an unresolved su
        // authorization prompt cannot block readText outside the deadline.
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            return false
        }
        return process.exitValue() == 0 && process.inputStream.bufferedReader().use { it.readText().trim() } == expectedUid
    } finally {
        process.destroy()
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }
}

internal enum class SuCommandMode {
    C_FLAG, UID_COMMAND;

    fun arguments(command: String, uid: String = "0"): Array<String> = when (this) {
        C_FLAG -> if (uid == "0") arrayOf("su", "-c", command) else arrayOf("su", uid, "-c", command)
        UID_COMMAND -> arrayOf("su", uid, "sh", "-c", command)
    }
}

/** Detect with a read-only UID command; never retry an accepted input. */
internal fun detectSuCommandMode(uid: String = "0", timeoutMillis: Long = 30_000): SuCommandMode {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    return SuCommandMode.entries.firstOrNull { mode ->
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        remaining > 0 && runCatching {
            val process = ProcessBuilder(*mode.arguments("id -u", uid)).redirectErrorStream(true).start()
            probeUid(process, remaining, uid)
        }.getOrDefault(false)
    } ?: error(if (uid == "0") "ROOT_PERMISSION_DENIED" else "SHELL_IDENTITY_REQUIRED")
}
