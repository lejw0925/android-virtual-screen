package io.github.lejw0925.virtualscreen.backend

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import io.github.lejw0925.virtualscreen.core.ScreenAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Shared protocol mapping; the transport chooses pipes or a bound Binder. */
abstract class CommandBackend(protected val context: Context) : VirtualScreenBackend {
    protected abstract suspend fun transact(request: JSONObject): JSONObject
    protected abstract suspend fun connect(config: VirtualScreenConfig): JSONObject
    protected abstract suspend fun disconnect()
    @Volatile protected var active: VirtualScreenHandle? = null
    @Volatile protected var observation: ScreenObservation? = null

    override suspend fun create(config: VirtualScreenConfig): Result<VirtualScreenHandle> = try {
        result {
            check(active == null) { "DISPLAY_ALREADY_ACTIVE" }
            val response = connect(config).checked()
            VirtualScreenHandle(response.getString("handleId"), response.getInt("displayId"), config).also { check(it.displayId > 0); active = it }
        }.onFailure { withContext(NonCancellable) { disconnect() } }
    } catch(cancelled: CancellationException) {
        // Cancellation can occur after the privileged process created its display
        // but before the caller received a handle. It still owns cleanup here.
        withContext(NonCancellable) { disconnect() }
        throw cancelled
    }

    override suspend fun close(handle: VirtualScreenHandle): BackendResult {
        try { if (active?.id == handle.id) transact(packet(handle, "close")) }
        finally { active = null; observation = null; disconnect() }
        return BackendResult.success("closed" to true)
    }

    override suspend fun checkAlive(handle: VirtualScreenHandle): Boolean = try {
        transact(packet(handle, "probe")).checked(); true
    } catch(c: CancellationException) { throw c } catch(_: Exception) { false }

    override suspend fun launch(handle: VirtualScreenHandle, component: String, uri: String?, restartApproved: Boolean): BackendResult = response {
        transact(packet(handle, "launch").put("component", component).put("uri", uri.orEmpty())
            .put("restartApproved", restartApproved).put("ownerPackage", context.packageName))
    }

    override suspend fun observe(handle: VirtualScreenHandle): Result<ScreenObservation> = result {
        val data = transact(packet(handle, "observe")).checked()
        val bytes = Base64.decode(data.getString("image"), Base64.DEFAULT)
        ScreenObservation(UUID.randomUUID().toString(), handle.id, 0, data.getInt("width"), data.getInt("height"), data.getInt("rotation"),
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("DISPLAY_FRAME_UNAVAILABLE"),
            frameFingerprint = data.optString("fingerprint"),deviceInteractive = if(data.has("interactive")) data.getBoolean("interactive") else null).also { observation = it }
    }

    override suspend fun inspect(handle: VirtualScreenHandle): Result<ScreenObservation> = result {
        val data = transact(packet(handle, "validate")).checked()
        ScreenObservation(UUID.randomUUID().toString(), handle.id, 0, data.getInt("width"), data.getInt("height"), data.getInt("rotation"),
            deviceInteractive = data.getBoolean("interactive")).also { observation = it }
    }

    override suspend fun showPreview(handle: VirtualScreenHandle): BackendResult = response { transact(packet(handle, "preview")) }

    override suspend fun nodes(handle: VirtualScreenHandle, observationId: String): Result<NodeSnapshot> = result {
        check(active?.id == handle.id) { "STALE_VIRTUAL_SCREEN" }
        val service = ScreenAccessibilityService.current ?: error("ACCESSIBILITY_NOT_ENABLED")
        service.snapshot(handle.displayId, observationId)
    }

    override suspend fun operate(handle: VirtualScreenHandle, operation: BackendOperation): BackendResult {
        if (operation is BackendOperation.NodeAction || operation is BackendOperation.Text) {
            val validation = packet(handle,"validate")
            observation?.let { validation.put("expectedWidth",it.width).put("expectedHeight",it.height).put("expectedRotation",it.rotation) }
            transact(validation).checked()
        }
        if (operation is BackendOperation.NodeAction) {
            val service = ScreenAccessibilityService.current ?: return BackendResult.failure("ACCESSIBILITY_NODES_UNAVAILABLE")
            return service.act(handle.displayId, operation)
        }
        if (operation is BackendOperation.Text) {
            ScreenAccessibilityService.current?.setFocusedText(handle.displayId, operation.value, operation.replace)?.let { if (it.ok || it.code != "FOCUSED_EDITOR_UNAVAILABLE") return it }
        }
        return response {
            val p = when (operation) {
                is BackendOperation.Tap -> packet(handle, "tap").put("x", operation.x).put("y", operation.y)
                is BackendOperation.LongPress -> packet(handle, "long_press").put("x", operation.x).put("y", operation.y).put("durationMs", operation.durationMs)
                is BackendOperation.Swipe -> packet(handle, "swipe").put("points", JSONArray(operation.points.map { listOf(it.first, it.second) })).put("durationMs", operation.durationMs)
                is BackendOperation.Scroll -> packet(handle, "scroll").put("x", operation.x).put("y", operation.y).put("delta", operation.delta)
                is BackendOperation.Key -> packet(handle, "key").put("keyCode", operation.keyCode)
                BackendOperation.Back -> packet(handle, "key").put("keyCode", 4)
                is BackendOperation.Text -> packet(handle, "text").put("value", operation.value).put("replace", operation.replace)
                is BackendOperation.NodeAction -> error("UNREACHABLE")
            }
            observation?.let { p.put("expectedWidth", it.width).put("expectedHeight", it.height).put("expectedRotation", it.rotation) }
            transact(p)
        }
    }

    protected fun configPacket(config: VirtualScreenConfig) = JSONObject().put("action", "create")
        .put("width", config.width).put("height", config.height).put("density", config.density)
        .put("rotation", config.rotation).put("allowScreenOff", config.allowScreenOff).put("idleTimeoutMinutes", config.idleTimeoutMinutes)
    protected fun packet(handle: VirtualScreenHandle, action: String): JSONObject {
        check(active?.id == handle.id) { "STALE_VIRTUAL_SCREEN" }
        return JSONObject().put("action", action).put("handleId", handle.id)
    }
    protected fun JSONObject.checked(): JSONObject { if (!optBoolean("ok")) throw BackendException(optString("code", "BACKEND_OPERATION_FAILED"), optString("detail", optString("code", "BACKEND_OPERATION_FAILED"))); return this }
    private suspend fun response(block: suspend () -> JSONObject): BackendResult = try {
        val json = block()
        BackendResult(json.optBoolean("ok"), json.optString("code").takeIf { it.isNotBlank() }, values = json.keys().asSequence().associateWith { json.opt(it) }.filterKeys { it != "image" })
    } catch (c: CancellationException) { throw c } catch (failure: Exception) { failure.asBackendFailure() }
    private suspend fun <T> result(block: suspend () -> T): Result<T> = try { Result.success(block()) } catch (c: CancellationException) { throw c } catch (failure: Exception) { Result.failure(failure) }
}
