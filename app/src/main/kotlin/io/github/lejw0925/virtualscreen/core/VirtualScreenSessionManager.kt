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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
    val previewStatus: PreviewStatus = PreviewStatus.HIDDEN,
    val toolProbe: McpToolProbeReport? = null,
)

/** Serializes all backend calls; a takeover fences work already in flight. */
class VirtualScreenSessionManager(
    private val context: Context,
    root: VirtualScreenBackend = RootBackend(context.applicationContext),
    shizuku: VirtualScreenBackend = ShizukuBackend(context.applicationContext),
    private val leases: ControlLeaseManager = ControlLeaseManager(),
    private val configuredDisplay: (() -> VirtualScreenConfig)? = null,
    val restartApprovals: AppRestartApprovalManager = AppRestartApprovalManager(),
    private val log: ActivityLogger = ActivityLogger.NONE,
    private val autoApproveRestart: () -> Boolean = { false },
) {
    companion object {
        const val PHONE_CLIENT = "phone"
        const val PROBE_CLIENT_PREFIX = "backend-probe-"
    }
    internal val accessibilityActivation = AccessibilityActivation(
        isConnected = { ScreenAccessibilityService.current != null },
        awaitConnection = { withTimeoutOrNull(3_000) { ScreenAccessibilityService.connected.first { it } } == true },
        log = log,
    )
    private val mutex = Mutex()
    private val launchMutex = Mutex()
    private val stateLock = Any()
    private val epoch = AtomicLong()
    private val backends = mapOf(BackendKind.ROOT to root, BackendKind.SHIZUKU to shizuku)
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()
    @Volatile private var handle: VirtualScreenHandle? = null
    private val phoneGestures = mutableMapOf<String, Int>()
    private val phoneViewers = mutableSetOf<String>()
    val phoneViewerActive: Boolean get() = synchronized(this) { phoneViewers.isNotEmpty() }
    private var latest: ScreenObservation? = null
    private var latestNodes: NodeSnapshot? = null
    private fun update(block: (SessionState) -> SessionState) = synchronized(stateLock) {
        val before = mutableState.value
        val after = block(before)
        mutableState.value = after
        if (before.backend != after.backend || before.capabilities != after.capabilities) {
            val caps = after.capabilities
            log.runtime("backend_capabilities", "changed", "backend=${after.backend}; display=${caps.displayCreation}; capture=${caps.frameCapture}; input=${caps.inputInjection}; nodes=${caps.accessibilityNodes}")
        }
        if (before.status != after.status || before.displayId != after.displayId) {
            log.runtime("virtual_display", after.status.name.lowercase(),
                "backend=${after.backend}; display=${after.displayId}; size=${after.config.width}x${after.config.height}@${after.config.density}")
        }
        if (before.previewStatus != after.previewStatus) log.runtime("preview", after.previewStatus.name.lowercase())
        if (after.errorCode != null && before.errorCode != after.errorCode) log.runtime("screen_error", "error", after.errorCode)
    }
    val liveChannel: LiveDisplayChannel? get() = if(state.value.status == SessionStatus.RUNNING) backend().liveChannel else null
    val defaultDisplayConfig get() = configuredDisplay?.invoke() ?: state.value.config
    val systemLanguage get() = io.github.lejw0925.virtualscreen.ui.systemLanguage(context)
    private fun backend() = backends.getValue(state.value.backend)
    @Synchronized private fun fence() {
        epoch.incrementAndGet()
        restartApprovals.cancelAll()
        synchronized(stateLock) { latest = null; latestNodes = null }
        update { it.copy(observationId = null, observationGeneration = epoch.get()) }
        backend().cancelActive()
    }

    suspend fun initializeBackend(kind: BackendKind) = mutex.withLock {
        if(handle == null && state.value.status == SessionStatus.STOPPED) {
            update { it.copy(backend=kind, toolProbe=it.toolProbe.takeIf { _ -> it.backend == kind }) }
            val caps=backend().probe()
            updateAuthorization(caps)
        }
    }
    suspend fun selectBackend(kind: BackendKind): BackendCapabilities = mutex.withLock {
        if (state.value.backend != kind) { fence(); closeInternal(); update { it.copy(backend = kind, toolProbe = null) } }
        val caps = backend().probe()
        updateAuthorization(caps)
        update { it.copy(errorCode = null) }
        caps
    }
    suspend fun refreshCapabilities(): BackendCapabilities = mutex.withLock {
        val caps = backend().probe()
        update { it.copy(capabilities = if (handle != null) it.capabilities.copy(accessibilityNodes = caps.accessibilityNodes) else caps, detail = caps.details) }
        state.value.capabilities
    }

    fun applyToolProbe(report: McpToolProbeReport) {
        update { it.copy(capabilities = report.capabilities, detail = report.capabilities.details, toolProbe = report) }
    }
    fun clearInactiveDisplayError() {
        update { current ->
            if (current.displayId == null && current.errorCode in setOf("NO_VIRTUAL_SCREEN", "DISPLAY_GONE"))
                current.copy(status = SessionStatus.STOPPED, errorCode = null)
            else current
        }
    }
    private fun updateAuthorization(caps: BackendCapabilities) {
        update { current ->
            val report = current.toolProbe.takeIf { caps.displayCreation != CapabilityStatus.REQUIRES_AUTHORIZATION }
            current.copy(capabilities = report?.capabilities ?: caps, detail = report?.capabilities?.details ?: caps.details, toolProbe = report)
        }
    }
    suspend fun requestAuthorization(): BackendCapabilities {
        log.runtime("backend_permission", "requested", "backend=${state.value.backend}")
        try {
            when (val selected = backend()) {
                is RootBackend -> check(selected.requestPermission()) { "ROOT_PERMISSION_DENIED" }
                is ShizukuBackend -> selected.requestPermission()
            }
            return ensureAccessibility().also {
                log.runtime("backend_permission", if (it.usable) "granted" else "pending", "backend=${state.value.backend}")
            }
        } catch (failure: Exception) {
            if (failure !is CancellationException && ScreenAccessibilityService.current == null) accessibilityActivation.reportFailure()
            log.failure("backend_permission", failure)
            throw failure
        }
    }
    suspend fun ensureAccessibility(): BackendCapabilities = mutex.withLock {
        val selected = backend()
        var caps = selected.probe()
        if (selected is RootBackend || selected is ShizukuBackend) {
            accessibilityActivation.ensure(state.value.backend, caps.displayCreation != CapabilityStatus.REQUIRES_AUTHORIZATION) { rebind ->
                when (selected) {
                    is RootBackend -> selected.enableAccessibility(rebind)
                    is ShizukuBackend -> selected.enableAccessibility(rebind)
                }
            }
            caps = selected.probe()
        }
        // Node access can be granted manually while the MCP server remains available.
        updateAuthorization(caps)
        caps
    }
    @Synchronized fun leaseStatus(): Lease? {
        retainPhoneViewerLease()
        val current = leases.snapshot()
        if (current?.generation != state.value.lease?.generation) fence()
        update { it.copy(lease = current) }
        return current
    }
    /** Publishes remote lease expiry even when the client stops making requests. */
    suspend fun monitorLeaseExpiry() {
        state.map { it.lease }.distinctUntilChanged().collectLatest { held ->
            if (held == null || held.owner == PHONE_CLIENT) return@collectLatest
            while (true) {
                val remaining = leases.remainingMillis(held.owner, held.token) ?: break
                delay(remaining.coerceAtLeast(1))
            }
            leaseStatus()
        }
    }
    @Synchronized fun acquireLease(clientId: String): LeaseDecision {
        retainPhoneViewerLease()
        val result = leases.acquire(clientId)
        if (result is LeaseDecision.Granted) {
            if (state.value.lease?.generation != result.lease.generation) fence()
            update { it.copy(lease = result.lease) }
        }
        return result
    }
    @Synchronized internal fun touchLease(clientId: String, token: String): LeaseDecision = leases.touch(clientId, token).also { if (it is LeaseDecision.Granted) update { s -> s.copy(lease = it.lease) } }
    @Synchronized fun releaseLease(clientId: String, token: String): LeaseDecision = leases.release(clientId, token).also {
        if (it is LeaseDecision.Granted) { fence(); update { s -> s.copy(lease = null) } }
    }
    @Synchronized fun beginPhoneGesture(): Lease {
        val held = leases.snapshot()
        val lease = if (held?.owner == PHONE_CLIENT) {
            (touchLease(held.owner, held.token) as? LeaseDecision.Granted)?.lease ?: phoneTakeover()
        } else phoneTakeover()
        phoneGestures[lease.token] = phoneGestures.getOrDefault(lease.token, 0) + 1
        return lease
    }
    @Synchronized fun finishPhoneControl(token: String) {
        val count = phoneGestures[token] ?: 0
        if (count > 1) phoneGestures[token] = count - 1 else phoneGestures.remove(token)
        if (phoneViewers.isNotEmpty() || phoneGestures.containsKey(token)) return
        leases.snapshot()?.takeIf { it.owner==PHONE_CLIENT && it.token==token }?.let { releaseLease(it.owner,it.token) }
    }
    /** Keeps the phone lease for the duration of a full-screen local viewer. */
    @Synchronized fun beginPhoneSession(viewer: String): Lease {
        phoneViewers.add(viewer)
        val current = leases.snapshot()
        if (current?.owner == PHONE_CLIENT) {
            update { it.copy(lease = current) }
            return current
        }
        return phoneTakeover()
    }
    /** Releases only the phone-owned lease when the viewer returns to PiP/background. */
    @Synchronized fun endPhoneSession(viewer: String, retainControl: Boolean = false) {
        if (!phoneViewers.remove(viewer) || phoneViewers.isNotEmpty()) return
        if (retainControl || phoneGestures.isNotEmpty()) return
        leases.snapshot()?.takeIf { it.owner == PHONE_CLIENT }?.let { releaseLease(it.owner, it.token) }
    }
    private fun retainPhoneViewerLease() {
        if (phoneViewers.isEmpty()) return
        val current = leases.snapshot()
        if (current?.owner == PHONE_CLIENT) leases.touch(current.owner, current.token)
        else phoneTakeover()
    }
    fun allowRemoteControl() {
        leases.snapshot()?.takeIf { it.owner==PHONE_CLIENT }?.let { finishPhoneControl(it.token) }
    }
    fun disconnectClient(clientId: String) {
        restartApprovals.forgetClient(clientId)
        leases.snapshot()?.takeIf { it.owner == clientId }?.let { releaseLease(clientId, it.token) }
    }
    @Synchronized fun phoneTakeover(): Lease {
        phoneGestures.clear()
        val lease = leases.takeover(PHONE_CLIENT)
        fence(); update { it.copy(lease = lease) }
        return lease
    }
    /** Approval is valid only for the controller shown in that notification. */
    @Synchronized fun approveControl(clientId: String, expectedGeneration: Long, releasePhoneViewer: Boolean = false): LeaseDecision {
        val held = leases.snapshot()
        if (held?.generation != expectedGeneration) return LeaseDecision.Rejected("CONTROL_CHANGED", held?.owner)
        if (phoneGestures.isNotEmpty()) return LeaseDecision.Rejected("PHONE_GESTURE_ACTIVE", held.owner)
        if (phoneViewers.isNotEmpty() && !releasePhoneViewer) return LeaseDecision.Rejected("PHONE_VIEWER_ACTIVE", held.owner)
        // Only an explicit decision in the phone dialog may end local viewing.
        if (releasePhoneViewer) phoneViewers.clear()
        val lease = leases.takeover(clientId)
        fence(); update { it.copy(lease = lease) }
        return LeaseDecision.Granted(lease)
    }
    fun cancelActive(owner: String? = null) { if(owner == null || leases.snapshot()?.owner == owner) fence() }
    private fun requireLease(owner: String, token: String) {
        check(touchLease(owner, token) is LeaseDecision.Granted) { "LEASE_REQUIRED" }
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
            log.failure("display_create", failure)
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
    /** A stale notification must never close a newer virtual display. */
    suspend fun closeFromPhone(sessionId: String) = mutex.withLock {
        if (state.value.sessionId != sessionId || state.value.displayId == null) return@withLock
        fence(); closeInternal(); leases.clear(); update { it.copy(lease = null) }
    }
    private suspend fun closeInternal() {
        val current = handle
        try { if (current != null) backend().close(current) }
        finally {
            handle = null; synchronized(stateLock) { latest = null; latestNodes = null }; epoch.incrementAndGet()
            restartApprovals.cancelAll()
            update { it.copy(status = SessionStatus.STOPPED, sessionId = null, displayId = null, observationId = null, frame = null, observationGeneration = epoch.get(),previewStatus=PreviewStatus.HIDDEN, errorCode = null) }
        }
    }
    suspend fun launch(owner: String, token: String, component: String, uri: String? = null, purpose: String = "", clientName: String = ""): OperationEnvelope {
        requireLease(owner, token)
        if (!launchMutex.tryLock()) return OperationEnvelope.failure("APP_LAUNCH_BUSY", state.value.sessionId)
        var ticket: AppRestartApprovalManager.Ticket? = null
        var autoApproved = false
        try {
            var expected = 0L
            var target: VirtualScreenHandle? = null
            val initial = mutex.withLock {
                requireLease(owner, token)
                expected = epoch.get()
                target = requireHandle()
                val result = backend().launch(target, component, uri)
                verify(owner, token, expected)
                invalidateObservation()
                if (result.code == "APP_RESTART_CONFIRMATION_REQUIRED") {
                    if (autoApproveRestart()) {
                        autoApproved = true
                        log.runtime("app_restart", "auto_approved", component)
                    } else {
                        val appName = runCatching {
                            val activity = context.packageManager.getActivityInfo(ComponentName.unflattenFromString(component)!!, 0)
                            activity.loadLabel(context.packageManager).toString()
                        }.getOrDefault(component.substringBefore('/'))
                        // Synchronize registration with phone takeover/lease changes.
                        synchronized(this@VirtualScreenSessionManager) {
                            verify(owner, token, expected)
                            ticket = restartApprovals.request(owner,
                                clientName.ifBlank { context.getString(io.github.lejw0925.virtualscreen.R.string.app_name) }, appName,
                                purpose.ifBlank { context.getString(io.github.lejw0925.virtualscreen.R.string.app_restart_purpose, appName) })
                        }
                    }
                }
                result.toEnvelope(state.value.sessionId)
            }
            val pending = ticket
            if (pending == null && !autoApproved) return initial
            // Release the backend mutex while the notification is awaiting a decision.
            val decision = if (pending != null) restartApprovals.await(pending) else "APPROVED"
            if (decision != "APPROVED") return OperationEnvelope.failure(decision, state.value.sessionId, "requires_observation" to true)
            return mutex.withLock {
                verify(owner, token, expected)
                check(handle == target) { "STALE_VIRTUAL_SCREEN" }
                requireLease(owner, token)
                val result = backend().launch(requireHandle(), component, uri, restartApproved = true)
                verify(owner, token, expected)
                invalidateObservation()
                result.toEnvelope(state.value.sessionId)
            }
        } finally {
            ticket?.let(restartApprovals::cancel)
            launchMutex.unlock()
        }
    }
    suspend fun observe(owner: String, token: String, captureFrame: Boolean = true): Result<OperationEnvelope> = safeResult {
        mutex.withLock {
            requireLease(owner, token)
            val expected = epoch.get()
            val observed = (if(captureFrame) backend().observe(requireHandle()) else backend().inspect(requireHandle())).getOrElse { failure ->
                if (failure.code() == "NO_RUNNING_APP") invalidateObservation()
                throw failure
            }
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
            update { it.copy(frame = frame.frame,frameRotation=frame.rotation,errorCode=null) }
        }.onFailure { failure ->
            if (failure.code() in setOf("APP_MOVED_TO_PHONE", "NO_RUNNING_APP")) {
                invalidateObservation()
                update { it.copy(frame=null,errorCode=failure.code()) }
            }
        }
    }
    suspend fun showRemotePreview(): BackendResult = mutex.withLock {
        val controller = leaseStatus()
        if (phoneViewerActive || controller == null || controller.owner == PHONE_CLIENT || controller.owner.startsWith(PROBE_CLIENT_PREFIX)) {
            return@withLock BackendResult.success("preview_requested" to false)
        }
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
    fun discoverApplications(includeIcons: Boolean = false): List<DiscoveredApplication> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
            .mapNotNull { info -> info.activityInfo?.let { ai -> DiscoveredApplication(ai.packageName, ComponentName(ai.packageName, ai.name).flattenToString(), info.loadLabel(pm).toString(), ai.exported, if(includeIcons) info.loadIcon(pm) else null) } }
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
data class DiscoveredApplication(val packageName: String, val component: String, val label: String, val exported: Boolean, val icon: android.graphics.drawable.Drawable? = null)
