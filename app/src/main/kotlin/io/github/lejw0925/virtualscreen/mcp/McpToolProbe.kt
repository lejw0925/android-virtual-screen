package io.github.lejw0925.virtualscreen.mcp

import io.github.lejw0925.virtualscreen.BuildConfig
import io.github.lejw0925.virtualscreen.backend.McpToolProbeReport
import io.github.lejw0925.virtualscreen.backend.ProbeEvidence
import io.github.lejw0925.virtualscreen.backend.ToolProbeResult
import io.github.lejw0925.virtualscreen.core.VirtualScreenSessionManager
import io.github.lejw0925.virtualscreen.ui.BackendProbeActivity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Exercises the same tool handlers as MCP clients, using disposable display controls. */
class McpToolProbe(private val sessions: VirtualScreenSessionManager) {
    suspend fun run(onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }): McpToolProbeReport {
        val registry = McpToolRegistry(sessions)
        val tools = registry.tools.map { it.name }
        val started = System.currentTimeMillis()
        val results = mutableListOf<ToolProbeResult>()
        val owner = VirtualScreenSessionManager.PROBE_CLIENT_PREFIX + UUID.randomUUID()
        var token: String? = null
        var created = false
        var evidence = ProbeEvidence()
        var blocked: String? = null
        val purpose = sessions.systemLanguage.text("检测权限后端", "Check backend capabilities", "檢測權限後端")

        fun report(blocked: String = "PROBE_PREREQUISITE_FAILED"): McpToolProbeReport {
            tools.filter { tool -> results.none { it.tool == tool } }.forEach {
                results += ToolProbeResult(it, passed = false, ok = false, code = blocked, attempted = false)
            }
            return McpToolProbeReport.from(results.sortedBy { tools.indexOf(it.tool) }, evidence, startedAtMillis = started)
        }
        suspend fun call(tool: String, arguments: JSONObject = JSONObject(), requestId: String = UUID.randomUUID().toString()): JSONObject {
            onProgress(results.map { it.tool }.distinct().size, tools.size, tool)
            arguments.put("purpose", purpose)
            if (tool !in setOf("acquire_control", "request_control", "get_status", "get_request_receipt")) {
                arguments.put("lease_token", token ?: "")
            }
            val data = registry.call(tool, arguments, owner, requestId).getJSONObject("structuredContent")
            val ok = data.optBoolean("ok")
            val code = data.optString("code").takeUnless { it.isBlank() || it == "null" }
            results += ToolProbeResult(tool, passed = ok, ok = ok, code = code)
            return data
        }
        fun verifyLast(tool: String, passed: Boolean, code: String) {
            val index = results.indexOfLast { it.tool == tool }
            if (index >= 0 && !passed) {
                val previous = results[index]
                results[index] = previous.copy(passed = false, code = if (previous.ok) code else previous.code ?: code)
            }
        }
        suspend fun observe(): JSONObject = call("observe", JSONObject().put("mode", "both").put("max_image_width", 480)).also {
            evidence = evidence.copy(
                capture = it.optBoolean("ok") && it.optInt("image_width") > 0,
                nodes = it.optBoolean("ok") && !it.has("nodes_error") && it.optJSONArray("nodes")?.length()?.let { n -> n > 0 } == true,
            )
            val complete = it.optBoolean("ok") && it.optInt("image_width") > 0 && !it.has("nodes_error")
            verifyLast("observe", complete, if (it.optBoolean("ok")) "PROBE_NODES_UNAVAILABLE" else "PROBE_CAPTURE_FAILED")
        }
        suspend fun waitFor(description: String? = null, text: String? = null): JSONObject {
            val arguments = JSONObject().put("timeout_ms", 4000)
            if (description != null) arguments.put("condition", "node_present").put("selector", JSONObject().put("description", description).apply {
                if (text != null) put("text", text)
            })
            else arguments.put("condition", "node_text").put("text", text)
            return call("wait", arguments)
        }
        fun node(data: JSONObject, description: String): JSONObject? {
            val nodes = data.optJSONArray("nodes") ?: return null
            return (0 until nodes.length()).map { nodes.getJSONObject(it) }.firstOrNull { it.optString("description") == description }
        }
        suspend fun awaitNode(description: String, ready: (JSONObject, JSONObject) -> Boolean): Boolean =
            withTimeoutOrNull(4000) {
                while (true) {
                    val observed = call("observe", JSONObject().put("mode", "tree"))
                    val target = node(observed, description)
                    if (observed.optBoolean("ok") && target != null && ready(observed, target)) return@withTimeoutOrNull true
                    delay(100)
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } ?: false
        fun center(data: JSONObject, description: String, fallbackY: Int): Pair<Int, Int> {
            val bounds = node(data, description)?.optJSONArray("bounds")
            return if (bounds != null && bounds.length() == 4) {
                (bounds.getInt(0) + bounds.getInt(2)) / 2 to (bounds.getInt(1) + bounds.getInt(3)) / 2
            } else 360 to fallbackY
        }
        suspend fun input(tool: String, build: (JSONObject, JSONObject) -> Unit): JSONObject {
            val observation = observe()
            val args = JSONObject().put("observation_id", observation.optString("observation_id")).put("observe_after", "none")
            build(args, observation)
            return call(tool, args)
        }

        // Never replace an existing display or take control from an agent/viewer.
        try {
            if (sessions.state.value.displayId != null) {
                blocked = "DISPLAY_ALREADY_ACTIVE"
            } else {
                val acquired = call("acquire_control")
                if (!acquired.optBoolean("ok")) {
                    blocked = acquired.optString("code", "CONTROL_BUSY")
                } else {
                    token = acquired.getString("lease_token")
                    // Recheck after obtaining the lease; another creator may have won the race.
                    if (sessions.state.value.displayId != null) {
                        blocked = "DISPLAY_ALREADY_ACTIVE"
                    } else {
                        call("request_control", JSONObject().put("action", "request"))
                        val statusId = UUID.randomUUID().toString()
                        call("get_status", requestId = statusId)
                        val receipt = call("get_request_receipt", JSONObject().put("request_id", statusId))
                        verifyLast("get_request_receipt", receipt.optBoolean("receipt_found"), "PROBE_RECEIPT_MISSING")
                        call("list_apps")
                        val display = call("create_screen", JSONObject().put("width", 720).put("height", 1600).put("density", 320).put("allow_screen_off", false))
                        created = display.optBoolean("ok")
                        evidence = evidence.copy(display = created)
                        if (created) {
                            call("configure_screen", JSONObject().put("density", 320).put("observe_after", "none"))
                            val launched = call("launch_app", JSONObject()
                                .put("component", "${BuildConfig.APPLICATION_ID}/${BackendProbeActivity::class.java.name}")
                                .put("observe_after", "none"))
                            if (launched.optBoolean("ok")) {
                                waitFor(description = BackendProbeActivity.COUNTER)
                                // A visible node may still carry translated launch-animation bounds.
                                var previousBounds: String? = null
                                val ready = awaitNode(BackendProbeActivity.COUNTER) { observed, target ->
                                    val bounds = target.getJSONArray("bounds")
                                    val stable = previousBounds == bounds.toString()
                                    previousBounds = bounds.toString()
                                    stable && bounds.getInt(0) >= 0 && bounds.getInt(1) >= 0 &&
                                        bounds.getInt(2) <= observed.optInt("width") && bounds.getInt(3) <= observed.optInt("height")
                                }
                                verifyLast("wait", ready, "PROBE_WINDOW_NOT_READY")
                                val tap = input("tap_at") { args, observed ->
                                    val (x, y) = center(observed, BackendProbeActivity.COUNTER, 190)
                                    args.put("x", x).put("y", y)
                                }
                                val tapped = waitFor(description = BackendProbeActivity.COUNTER, text = "1").optBoolean("ok")
                                verifyLast("tap_at", tap.optBoolean("ok") && tapped,
                                    if (tap.optBoolean("ok")) "PROBE_EFFECT_NOT_CONFIRMED" else "PROBE_INPUT_FAILED")
                                val longPress = input("long_press_at") { args, observed ->
                                    val (x, y) = center(observed, BackendProbeActivity.COUNTER, 190)
                                    args.put("x", x).put("y", y).put("duration_ms", 700)
                                }
                                val held = waitFor(description = BackendProbeActivity.LONG_PRESSED).optBoolean("ok")
                                verifyLast("long_press_at", held, "PROBE_EFFECT_NOT_CONFIRMED")
                                val nodeText = input("node_action") { args, _ ->
                                    args.put("selector", JSONObject().put("description", BackendProbeActivity.EDITOR))
                                        .put("action", "set_text").put("value", "MCP")
                                }
                                val nodeTextVerified = waitFor(description = BackendProbeActivity.EDITOR, text = "MCP").optBoolean("ok")
                                verifyLast("node_action", nodeText.optBoolean("ok") && nodeTextVerified, "PROBE_EFFECT_NOT_CONFIRMED")
                                input("tap_at") { args, observed ->
                                    val (x, y) = center(observed, BackendProbeActivity.EDITOR, 302)
                                    args.put("x", x).put("y", y)
                                }
                                // Focus acknowledgements precede accessibility's focus-change event.
                                val focused = awaitNode(BackendProbeActivity.EDITOR) { _, target -> target.optBoolean("focused") }
                                // The focus tap must not mask a failed or unconfirmed counter tap.
                                verifyLast("tap_at", tap.optBoolean("ok") && tapped && focused,
                                    if (tap.optBoolean("ok")) "PROBE_EFFECT_NOT_CONFIRMED" else "PROBE_INPUT_FAILED")
                                val text = "中文測試 MCP"
                                val typed = input("input_text") { args, _ -> args.put("text", text).put("replace", true) }
                                val textVerified = waitFor(description = BackendProbeActivity.EDITOR, text = text).optBoolean("ok")
                                verifyLast("input_text", textVerified, "PROBE_EFFECT_NOT_CONFIRMED")
                                val swipe = input("swipe_path") { args, observed ->
                                    val (x, y) = center(observed, BackendProbeActivity.SCROLL, 950)
                                    args.put("points", JSONArray(listOf(listOf(x, y + 100), listOf(x, y - 100)))).put("duration_ms", 300)
                                }
                                val scroll = input("scroll_at") { args, observed ->
                                    val (x, y) = center(observed, BackendProbeActivity.SCROLL, 950)
                                    args.put("x", x).put("y", y).put("delta", -1)
                                }
                                val key = input("press_key") { args, _ -> args.put("key_code", 21) }
                                evidence = evidence.copy(input = listOf(tap, longPress, typed, swipe, scroll, key).all { it.optBoolean("ok") } && tapped && held && textVerified)
                            } else {
                                blocked = launched.optString("code", "PROBE_ACTIVITY_LAUNCH_FAILED")
                            }
                        } else {
                            blocked = display.optString("code", "DISPLAY_CREATE_FAILED")
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            withContext(NonCancellable) {
                try {
                    if (created && sessions.leaseStatus()?.let { it.owner == owner && it.token == token } == true) call("close_screen")
                } finally {
                    if (token != null) call("release_control")
                    registry.forgetClient(owner)
                    sessions.disconnectClient(owner)
                }
            }
        }
        onProgress(tools.size, tools.size, "")
        return report(blocked ?: "PROBE_PREREQUISITE_FAILED")
    }
}
