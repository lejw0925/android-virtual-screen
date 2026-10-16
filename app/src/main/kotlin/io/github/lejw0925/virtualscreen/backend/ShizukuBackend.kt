package io.github.lejw0925.virtualscreen.backend

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import io.github.lejw0925.virtualscreen.BuildConfig
import io.github.lejw0925.virtualscreen.core.ScreenAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.UUID

class ShizukuBackend(context: Context) : CommandBackend(context) {
    @Volatile override var liveChannel: LiveDisplayChannel? = null
        private set
    @Volatile private var remote: IBinder? = null
    private var reader: ImageReader? = null
    private var frames: HandlerThread? = null
    private var capture: VirtualScreenFrameCapture? = null
    private val bindingLock = Mutex()
    private val bindingEpoch = AtomicLong()
    private var serviceArgs: Shizuku.UserServiceArgs? = null
    private var serviceConnection: ServiceConnection? = null
    override val kind = BackendKind.SHIZUKU

    override suspend fun probe(): BackendCapabilities {
        val ready = runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        return BackendCapabilities(
            displayCreation = if (ready) CapabilityStatus.UNKNOWN else CapabilityStatus.REQUIRES_AUTHORIZATION,
            frameCapture = if (ready) CapabilityStatus.UNKNOWN else CapabilityStatus.REQUIRES_AUTHORIZATION,
            inputInjection = if (ready) CapabilityStatus.UNKNOWN else CapabilityStatus.REQUIRES_AUTHORIZATION,
            accessibilityNodes = if (ScreenAccessibilityService.current != null) CapabilityStatus.AVAILABLE else CapabilityStatus.REQUIRES_AUTHORIZATION,
            details = if (ready) "SHIZUKU_READY" else "SHIZUKU_PERMISSION_REQUIRED")
    }

    fun requestPermission() { check(Shizuku.pingBinder()) { "SHIZUKU_NOT_RUNNING" }; Shizuku.requestPermission(8732) }

    private suspend fun bind() = bindingLock.withLock {
        if (remote?.isBinderAlive == true) return@withLock
        check(Shizuku.pingBinder()) { "SHIZUKU_NOT_RUNNING" }
        check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "SHIZUKU_PERMISSION_REQUIRED" }
        val expected = bindingEpoch.incrementAndGet()
        val connected = CompletableDeferred<IBinder>()
        // Removal of an old UserService is asynchronous in Shizuku. A new identity
        // prevents that removal from deleting an immediately rebound service.
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShizukuDisplayService::class.java.name))
            .daemon(false).tag("virtual-screen-${UUID.randomUUID()}").processNameSuffix("display")
            .version(BuildConfig.VERSION_CODE * 100 + 7)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null || bindingEpoch.get() != expected) return
                remote = service
                service.linkToDeath({
                    if (bindingEpoch.get() == expected && remote === service) { remote = null; active = null }
                }, 0)
                connected.complete(service)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (bindingEpoch.get() == expected) { remote = null; active = null }
            }
        }
        serviceArgs = args
        serviceConnection = connection
        try {
            withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
            remote = withTimeoutOrNull(15_000) { connected.await() } ?: error("SHIZUKU_SERVICE_BIND_TIMEOUT")
        } catch (failure: Exception) {
            bindingEpoch.incrementAndGet()
            remote = null
            serviceArgs = null
            serviceConnection = null
            withContext(NonCancellable + Dispatchers.Main) { runCatching { Shizuku.unbindUserService(args, connection, true) } }
            throw failure
        }
    }

    suspend fun enableAccessibility(rebind: Boolean = false) = withContext(Dispatchers.IO) {
        bind()
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuDisplayService.DESCRIPTOR)
            data.writeBoolean(rebind)
            check(remote!!.transact(ShizukuDisplayService.AUTHORIZE_ACCESSIBILITY, data, reply, 0)) { "SHIZUKU_TRANSACTION_FAILED" }
            reply.readException()
        } finally {
            data.recycle(); reply.recycle()
            if(active == null) withContext(NonCancellable) { disconnect() }
        }
    }

    override suspend fun connect(config: VirtualScreenConfig): JSONObject {
        bind()
        frames = HandlerThread("shizuku-capture").apply { start() }
        reader = ImageReader.newInstance(config.width, config.height, PixelFormat.RGBA_8888, 3)
        capture = VirtualScreenFrameCapture(reader!!, Handler(frames!!.looper)) { active?.displayId?.let { context.getSystemService(DisplayManager::class.java).getDisplay(it) } }
        return invoke(ShizukuDisplayService.CREATE, configPacket(config), true).also { if(it.optBoolean("ok")) liveChannel=LiveDisplayChannel(it.getString("handleId"),remote!!) }
    }

    override suspend fun observe(handle: VirtualScreenHandle): Result<ScreenObservation> = withContext(Dispatchers.IO) {
        runCatching {
            check(active?.id == handle.id) { "STALE_VIRTUAL_SCREEN" }
            val status = transact(packet(handle, "validate")).checked()
            val frame = capture?.capture(2000) ?: error("DISPLAY_FRAME_PENDING")
            ScreenObservation(UUID.randomUUID().toString(), handle.id, 0, frame.width, frame.height, frame.rotation,
                BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size), frameFingerprint = frame.fingerprint.toString(),deviceInteractive = if(status.has("interactive")) status.getBoolean("interactive") else null).also { observation = it }
        }
    }
    override suspend fun transact(request: JSONObject) = withContext(Dispatchers.IO) { invoke(ShizukuDisplayService.EXECUTE, request) }
    override fun cancelActive() { runCatching { invoke(ShizukuDisplayService.CANCEL, JSONObject()) } }
    private fun invoke(code: Int, request: JSONObject, surface: Boolean = false): JSONObject {
        val binder = remote ?: error("SHIZUKU_SERVICE_DIED")
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuDisplayService.DESCRIPTOR)
            if (code != ShizukuDisplayService.CANCEL) data.writeString(request.toString())
            if (surface) data.writeTypedObject(reader!!.surface, 0)
            check(binder.transact(code, data, reply, 0)) { "SHIZUKU_TRANSACTION_FAILED" }
            reply.readException()
            return if (code == ShizukuDisplayService.CANCEL) JSONObject().put("ok", true) else JSONObject(reply.readString() ?: error("INVALID_REPLY"))
        } finally { data.recycle(); reply.recycle() }
    }
    override suspend fun disconnect() = bindingLock.withLock {
        capture?.close(); capture = null; reader?.close(); reader = null; frames?.quitSafely(); frames = null
        liveChannel = null
        bindingEpoch.incrementAndGet()
        remote = null
        val args = serviceArgs
        val connection = serviceConnection
        serviceArgs = null
        serviceConnection = null
        if (args != null && connection != null) withContext(NonCancellable + Dispatchers.Main) {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }
}
