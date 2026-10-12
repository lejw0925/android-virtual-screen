package io.github.lejw0925.virtualscreen.core

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import io.github.lejw0925.virtualscreen.backend.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SessionStatus { STOPPED, STARTING, RUNNING, ERROR }
enum class PreviewStatus { HIDDEN, REQUESTED, VISIBLE, UNAVAILABLE }
data class SessionState(
    val backend: BackendKind = BackendKind.ROOT,
    val capabilities: BackendCapabilities = BackendCapabilities(),
    val status: SessionStatus = SessionStatus.STOPPED,
    val sessionId: String? = null,
    val displayId: Int? = null,
    val config: VirtualScreenConfig = VirtualScreenConfig(),
    val lease: Lease? = null,
    val observationId: String? = null,
    val observationGeneration: Long = 0,
    val frame: Bitmap? = null,
    val frameRotation: Int = 0,
    val errorCode: String? = null,
    val detail: String? = null,
    val pendingRestart: String? = null,
    val previewStatus: PreviewStatus = PreviewStatus.HIDDEN,
)

/** Serializes all backend calls; a takeover fences work already in flight. */
class VirtualScreenSessionManager(
    private val context: Context,
    root: VirtualScreenBackend = RootBackend(context.applicationContext),
    shizuku: VirtualScreenBackend = ShizukuBackend(context.applicationContext),
    private val leases: ControlLeaseManager = ControlLeaseManager(),
    private val configuredDisplay: (() -> VirtualScreenConfig)? = null,
) {
    companion object { const val PHONE_CLIENT = "phone" }
    private val mutex = Mutex()
    private val stateLock = Any()
    private val epoch = AtomicLong()
    private val backends = mapOf(BackendKind.ROOT to root, BackendKind.SHIZUKU to shizuku)
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()
    @Volatile private var handle: VirtualScreenHandle? = null
    private var latest: ScreenObservation? = null
    private var latestNodes: NodeSnapshot? = null
    private fun update(block: (SessionState) -> SessionState) = synchronized(stateLock) { mutableState.value = block(mutableState.value) }
    val liveChannel: LiveDisplayChannel? get() = if(state.value.status == SessionStatus.RUNNING) backend().liveChannel else null
    val defaultDisplayConfig get() = configuredDisplay?.invoke() ?: state.value.config
    val systemLanguage get() = io.github.lejw0925.virtualscreen.ui.systemLanguage(context)
    private fun backend() = backends.getValue(state.value.backend)
    private fun fence() {
        epoch.incrementAndGet()
        synchronized(stateLock) { latest = null; latestNodes = null }
        update { it.copy(observationId = null, observationGeneration = epoch.get()) }
        backend().cancelActive()
    }

    suspend fun initializeBackend(kind: BackendKind) = mutex.withLock {
        if(handle == null && state.value.status == SessionStatus.STOPPED) {
            update { it.copy(backend=kind) }
            val caps=backend().probe()
            update { it.copy(capabilities=caps,detail=caps.details) }
        }
    }
    suspend fun selectBackend(kind: BackendKind): BackendCapabilities = mutex.withLock {
        if (state.value.backend != kind) { fence(); closeInternal(); update { it.copy(backend = kind) } }
        val caps = backend().probe()
        update { it.copy(capabilities = caps, detail = caps.details, errorCode = null) }
        caps
    }
    suspend fun refreshCapabilities(): BackendCapabilities = mutex.withLock {
        val caps = backend().probe()
        update { it.copy(capabilities = if (handle != null) it.capabilities.copy(accessibilityNodes = caps.accessibilityNodes) else caps, detail = caps.details) }
        state.value.capabilities
    }
    suspend fun requestAuthorization(): BackendCapabilities {
        when (val selected = backend()) {
            is RootBackend -> check(selected.requestPermission()) { "ROOT_PERMISSION_DENIED" }
            is ShizukuBackend -> selected.requestPermission()
        }
        return refreshCapabilities()
    }
    fun leaseStatus(): Lease? {
        val current = leases.snapshot()
        if (current?.generation != state.value.lease?.generation) fence()
        update { it.copy(lease = current) }
        return current
    }
    fun acquireLease(clientId: String): LeaseDecision {
        val result = leases.acquire(clientId)
        if (result is LeaseDecision.Granted) {
            if (state.value.lease?.generation != result.lease.generation) fence()
            update { it.copy(lease = result.lease) }
        }
        return result
    }
    fun renewLease(clientId: String, token: String): LeaseDecision = leases.renew(clientId, token).also { if (it is LeaseDecision.Granted) update { s -> s.copy(lease = it.lease) } }
    fun releaseLease(clientId: String, token: String): LeaseDecision = leases.release(clientId, token).also {
        if (it is LeaseDecision.Granted) { fence(); update { s -> s.copy(lease = null) } }
    }
    fun finishPhoneControl(token: String) {
        leases.snapshot()?.takeIf { it.owner==PHONE_CLIENT && it.token==token }?.let { releaseLease(it.owner,it.token) }
    }
    fun allowRemoteControl() {
        leases.snapshot()?.takeIf { it.owner==PHONE_CLIENT }?.let { finishPhoneControl(it.token) }
    }
    fun disconnectClient(clientId: String) { leases.snapshot()?.takeIf { it.owner == clientId }?.let { releaseLease(clientId, it.token) } }
    fun phoneTakeover(): Lease {
        val lease = leases.takeover(PHONE_CLIENT)
        fence(); update { it.copy(lease = lease) }
        return lease
    }
    fun cancelActive(owner: String? = null) { if(owner == null || leases.snapshot()?.owner == owner) fence() }
    fun dismissRestart() { update { it.copy(pendingRestart = null) } }
    private fun requireLease(owner: String, token: String) {
        check(leases.check(owner, token)) { "LEASE_REQUIRED" }
        val renewed = leases.renew(owner, token)
        if (renewed is LeaseDecision.Granted) update { it.copy(lease = renewed.lease) }
    }
    private fun requireObservation(id: String?) = synchronized(stateLock) {
        check(id != null && latest?.id == id && latest?.generation == epoch.get()) { "STALE_OBSERVATION" }
    }
    private fun requireHandle() = handle ?: error("NO_VIRTUAL_SCREEN")
    private fun verify(owner: String, token: String, expected: Long) { check(epoch.get() == expected && leases.check(owner, token)) { "CONTROL_CHANGED" } }

    suspend fun create(owner: String, token: String, config: VirtualScreenConfig = defaultDisplayConfig): OperationEnvelope = mutex.withLock {
        requireLease(owner, token)
        check(handle == null) { "DISPLAY_ALREADY_ACTIVE" }
        val expected = epoch.get()
        update { it.copy(status = SessionStatus.STARTING, config = config, errorCode = null) }
        val selected = backend()
        try {
            val created = selected.create(config).getOrThrow()
            handle = created
            try { verify(owner, token, expected) } catch (failure: Exception) { withContext(NonCancellable) { closeInternal() }; throw failure }
            val sid = UUID.randomUUID().toString()
            epoch.incrementAndGet()
            update { it.copy(status = SessionStatus.RUNNING, sessionId = sid, displayId = created.displayId,
                config = config, observationId = null, observationGeneration = epoch.get(),
                capabilities = it.capabilities.copy(displayCreation = CapabilityStatus.AVAILABLE, frameCapture = CapabilityStatus.UNKNOWN, inputInjection = CapabilityStatus.UNKNOWN,
                    screenOff = if (config.allowScreenOff) CapabilityStatus.UNKNOWN else CapabilityStatus.UNAVAILABLE)) }
            OperationEnvelope(true, sessionId = sid, values = mapOf("display_id" to created.displayId, "generation" to epoch.get()))
        } catch (failure: CancellationException) {
            withContext(NonCancellable) { closeInternal() }; throw failure
        } catch (failure: Exception) {
            update { it.copy(status = SessionStatus.ERROR, errorCode = failure.code(), detail = failure.message) }
            OperationEnvelope.failure(failure.code())
        }
    }
    suspend fun configure(owner: String, token: String, config: VirtualScreenConfig): OperationEnvelope {
        close(owner, token)
        return create(owner, token, config)
    }
    suspend fun close(owner: String, token: String): OperationEnvelope = mutex.withLock {
        requireLease(owner, token); fence(); closeInternal(); OperationEnvelope.success("closed" to true)
    }
    suspend fun shutdown() = mutex.withLock { fence(); closeInternal(); leases.clear(); update { it.copy(lease = null) } }
    private suspend fun closeInternal() {
        val current = handle
        try { if (current != null) backend().close(current) }
        finally {
            handle = null; synchronized(stateLock) { latest = null; latestNodes = null }; epoch.incrementAndGet()
            update { it.copy(status = SessionStatus.STOPPED, sessionId = null, displayId = null, observationId = null, frame = null, observationGeneration = epoch.get(),previewStatus=PreviewStatus.HIDDEN,pendingRestart=null) }
        }
    }
    suspend fun launch(owner: String, token: String, component: String, uri: String? = null, restartApproved: Boolean = false): OperationEnvelope = mutex.withLock {
        requireLease(owner, token)
        // Remote callers cannot approve stopping an app on another display.
        check(!restartApproved || owner == PHONE_CLIENT) { "PHONE_CONFIRMATION_REQUIRED" }
        val expected = epoch.get()
        val result = backend().launch(requireHandle(), component, uri, restartApproved)
        verify(owner, token, expected)
        update { it.copy(pendingRestart = component.takeIf { result.code == "APP_RESTART_CONFIRMATION_REQUIRED" }) }
        invalidateObservation()
        result.toEnvelope(state.value.sessionId)
    }
    suspend fun observe(owner: String, token: String, captureFrame: Boolean = true): Result<OperationEnvelope> = safeResult {
        mutex.withLock {
            requireLease(owner, token)
            val expected = epoch.get()
            val observed = (if(captureFrame) backend().observe(requireHandle()) else backend().inspect(requireHandle())).getOrThrow()
            verify(owner, token, expected)
            val fenced = observed.copy(id = UUID.randomUUID().toString(), sessionId = state.value.sessionId.orEmpty(), generation = expected)
            synchronized(stateLock) { latest = fenced; latestNodes = null }
            update { it.copy(observationId = fenced.id, frame = fenced.frame ?: it.frame, frameRotation=fenced.rotation, errorCode = null,
                capabilities = it.capabilities.copy(frameCapture = if(captureFrame) CapabilityStatus.AVAILABLE else it.capabilities.frameCapture,
                    screenOff = if(observed.deviceInteractive == false) CapabilityStatus.AVAILABLE else it.capabilities.screenOff)) }
            OperationEnvelope(true, sessionId = fenced.sessionId, values = mapOf("observation_id" to fenced.id, "generation" to expected,
                "width" to fenced.width, "height" to fenced.height, "rotation" to fenced.rotation, "frame" to fenced.frame, "mime_type" to fenced.mimeType,
                "fingerprint" to fenced.frameFingerprint))
        }
    }
    suspend fun preview() = mutex.withLock {
        val current = handle ?: return@withLock
        val result = backend().observe(current)
        result.onSuccess { frame ->
            synchronized(stateLock) { latest?.let { if (it.width != frame.width || it.height != frame.height || it.rotation != frame.rotation) invalidateObservation() } }
            update { it.copy(frame = frame.frame,frameRotation=frame.rotation) }
        }
    }
    suspend fun showRemotePreview(): BackendResult = mutex.withLock {
        val result=backend().showPreview(requireHandle())
        update { it.copy(previewStatus=if(!result.ok) PreviewStatus.UNAVAILABLE else if(it.previewStatus==PreviewStatus.VISIBLE) PreviewStatus.VISIBLE else PreviewStatus.REQUESTED) }
        result
    }
    fun previewVisibility(visible: Boolean) { update { it.copy(previewStatus=if(visible) PreviewStatus.VISIBLE else PreviewStatus.HIDDEN) } }
    suspend fun nodes(owner: String, token: String, observationId: String): Result<NodeSnapshot> = safeResult {
        mutex.withLock {
            requireLease(owner, token); requireObservation(observationId)
            synchronized(stateLock) { latestNodes?.takeIf { it.observationId==observationId } }?.let { return@withLock it }
            val expected = epoch.get()
            val nodes = backend().nodes(requireHandle(), observationId).getOrThrow()
            verify(owner, token, expected)
            synchronized(stateLock) { latestNodes = nodes }
            update { it.copy(capabilities=it.capabilities.copy(accessibilityNodes=CapabilityStatus.AVAILABLE)) }; nodes
        }
    }
    suspend fun operate(owner: String, token: String, observationId: String?, operation: BackendOperation): OperationEnvelope = mutex.withLock {
        requireLease(owner, token); requireObservation(observationId)
        val expected = epoch.get()
        try {
            val result = backend().operate(requireHandle(), operation)
            verify(owner, token, expected)
            update { it.copy(capabilities = it.capabilities.copy(inputInjection = if (result.ok) CapabilityStatus.AVAILABLE else it.capabilities.inputInjection)) }
            invalidateObservation()
            result.toEnvelope(state.value.sessionId)
        } catch (cancelled: CancellationException) { backend().cancelActive(); invalidateObservation(); throw cancelled }
    }
    private fun invalidateObservation() { synchronized(stateLock) { latest = null; latestNodes = null }; update { it.copy(observationId = null) } }
    suspend fun healthCheck() = mutex.withLock {
        leaseStatus()
        val current = handle ?: return@withLock
        if (!backend().checkAlive(current)) {
            fence(); closeInternal()
            update { it.copy(status = SessionStatus.ERROR, errorCode = "BACKEND_PERMISSION_OR_DISPLAY_LOST", detail = "BACKEND_PERMISSION_OR_DISPLAY_LOST") }
        }
    }
    fun discoverApplications(): List<DiscoveredApplication> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
            .mapNotNull { info -> info.activityInfo?.let { ai -> DiscoveredApplication(ai.packageName, ComponentName(ai.packageName, ai.name).flattenToString(), info.loadLabel(pm).toString(), ai.exported) } }
            .distinctBy { it.component }.sortedBy { it.label.lowercase() }.take(500)
    }
}

data class OperationEnvelope(val ok: Boolean, val code: String? = null, val sessionId: String? = null, val values: Map<String, Any?> = emptyMap()) {
    companion object {
        fun success(vararg values: Pair<String, Any?>) = OperationEnvelope(true, values = values.toMap())
        fun failure(code: String, sessionId: String? = null, vararg values: Pair<String, Any?>) = OperationEnvelope(false, code, sessionId, values.toMap())
    }
}
private fun BackendResult.toEnvelope(sid: String?) = OperationEnvelope(ok, code, sid, values + (message?.let { mapOf("message" to it) } ?: emptyMap()))
fun Throwable.code(): String = (this as? BackendException)?.code ?: message?.takeIf { it.matches(Regex("[A-Z0-9_]+")) } ?: "BACKEND_OPERATION_FAILED"
private suspend fun <T> safeResult(block: suspend () -> T): Result<T> = try { Result.success(block()) } catch (c: CancellationException) { throw c } catch (e: Exception) { Result.failure(e) }
data class DiscoveredApplication(val packageName: String, val component: String, val label: String, val exported: Boolean)
