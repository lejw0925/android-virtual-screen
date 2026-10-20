package io.github.lejw0925.virtualscreen.backend

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.CancellationException

enum class BackendKind(val wireName: String) {
    ROOT("root"),
    SHIZUKU("shizuku"),
}

enum class CapabilityStatus {
    AVAILABLE,
    REQUIRES_AUTHORIZATION,
    UNAVAILABLE,
    UNKNOWN,
}

data class BackendCapabilities(
    val displayCreation: CapabilityStatus = CapabilityStatus.UNKNOWN,
    val frameCapture: CapabilityStatus = CapabilityStatus.UNKNOWN,
    val inputInjection: CapabilityStatus = CapabilityStatus.UNKNOWN,
    val accessibilityNodes: CapabilityStatus = CapabilityStatus.UNKNOWN,
    val screenOff: CapabilityStatus = CapabilityStatus.UNKNOWN,
    val details: String = "",
) {
    val usable: Boolean
        get() = displayCreation == CapabilityStatus.AVAILABLE
}

data class VirtualScreenConfig(
    val width: Int = 720,
    val height: Int = 1600,
    val density: Int = 320,
    val rotation: Int = 0,
    val allowScreenOff: Boolean = false,
    val idleTimeoutMinutes: Int = 20,
) {
    init {
        require(width in 320..4096) { "INVALID_WIDTH" }
        require(height in 480..4096) { "INVALID_HEIGHT" }
        require(density in 120..640) { "INVALID_DENSITY" }
        require(rotation in 0..3) { "INVALID_ROTATION" }
        require(idleTimeoutMinutes in 0..240) { "INVALID_IDLE_TIMEOUT" }
    }
}

data class VirtualScreenHandle(
    val id: String,
    val displayId: Int,
    val config: VirtualScreenConfig,
)

data class ScreenObservation(
    val id: String,
    val sessionId: String,
    val generation: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val frame: Bitmap? = null,
    val mimeType: String = "image/jpeg",
    val frameFingerprint: String? = null,
    val deviceInteractive: Boolean? = null,
)

data class NodeSnapshot(
    val observationId: String,
    val displayId: Int,
    val packageName: String? = null,
    val nodes: List<NodeInfo> = emptyList(),
    val truncated: Boolean = false,
    val truncationReasons: List<String> = emptyList(),
    val windows: List<WindowSnapshot> = emptyList(),
    val nodeLimit: Int = 5000,
    val depthLimit: Int = 100,
    val childLimit: Int = 1000,
)

data class WindowSnapshot(
    val id: Int,
    val displayId: Int,
    val type: Int,
    val packageName: String? = null,
    val title: String? = null,
    val layer: Int = 0,
    val focused: Boolean = false,
    val active: Boolean = false,
)

data class NodeActionInfo(val id: Int, val label: String? = null)

data class NodeInfo(
    val id: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val bounds: Rect? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val resourceId: String? = null,
    val parentId: String? = null,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val visible: Boolean = true,
    val password: Boolean = false,
    val hintText: String? = null,
    val stateDescription: String? = null,
    val longClickable: Boolean = false,
    val focusable: Boolean = false,
    val windowId: Int? = null,
    val packageName: String? = null,
    val actionList: List<NodeActionInfo> = emptyList(),
)

sealed interface BackendOperation {
    data class Tap(val x: Int, val y: Int) : BackendOperation
    data class LongPress(val x: Int, val y: Int, val durationMs: Int = 600) : BackendOperation
    data class Swipe(val points: List<Pair<Int, Int>>, val durationMs: Int = 300) : BackendOperation
    data class Scroll(val x: Int, val y: Int, val delta: Int) : BackendOperation
    data class Text(val value: String, val replace: Boolean = false) : BackendOperation
    data class Key(val keyCode: Int) : BackendOperation
    data object Back : BackendOperation
    data class NodeAction(val nodeId: String, val action: String, val value: String? = null) : BackendOperation
}

data class BackendResult(
    val ok: Boolean,
    val code: String? = null,
    val message: String? = null,
    val values: Map<String, Any?> = emptyMap(),
) {
    companion object {
        fun success(vararg values: Pair<String, Any?>) = BackendResult(true, values = values.toMap())
        fun failure(code: String, message: String? = null) = BackendResult(false, code, message)
    }
}

/**
 * All display operations are scoped to this object. Implementations must never
 * fall back to the phone's default display after a display operation fails.
 */
interface VirtualScreenBackend {
    val kind: BackendKind
    val liveChannel: LiveDisplayChannel? get() = null

    suspend fun probe(): BackendCapabilities
    suspend fun create(config: VirtualScreenConfig): Result<VirtualScreenHandle>
    suspend fun close(handle: VirtualScreenHandle): BackendResult
    suspend fun launch(handle: VirtualScreenHandle, component: String, uri: String? = null, restartApproved: Boolean = false): BackendResult
    suspend fun observe(handle: VirtualScreenHandle): Result<ScreenObservation>
    /** Validate display geometry without encoding an image for tree-only observations. */
    suspend fun inspect(handle: VirtualScreenHandle): Result<ScreenObservation> = observe(handle)
    suspend fun showPreview(handle: VirtualScreenHandle): BackendResult = BackendResult.failure("PREVIEW_UNAVAILABLE")
    suspend fun nodes(handle: VirtualScreenHandle, observationId: String): Result<NodeSnapshot>
    suspend fun operate(handle: VirtualScreenHandle, operation: BackendOperation): BackendResult
    fun cancelActive() {}
    suspend fun checkAlive(handle: VirtualScreenHandle): Boolean = true
}

internal fun Throwable.asBackendFailure(): BackendResult {
    if (this is CancellationException) throw this
    val code = message?.takeIf { it.matches(Regex("[A-Z0-9_]+")) } ?: "BACKEND_OPERATION_FAILED"
    return BackendResult.failure(code, message)
}
