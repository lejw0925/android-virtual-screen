package io.github.lejw0925.virtualscreen.core

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.lejw0925.virtualscreen.backend.BackendOperation
import io.github.lejw0925.virtualscreen.backend.BackendResult
import io.github.lejw0925.virtualscreen.backend.NodeInfo
import io.github.lejw0925.virtualscreen.backend.NodeActionInfo
import io.github.lejw0925.virtualscreen.backend.NodeSnapshot
import io.github.lejw0925.virtualscreen.backend.WindowSnapshot
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.LinkedHashSet

/** Every window lookup includes the owned virtual display, including focused text. */
class ScreenAccessibilityService : AccessibilityService() {
    private data class Saved(val node: AccessibilityNodeInfo, val signature: String, val displayId: Int)
    private val saved = mutableMapOf<String, Saved>()
    private fun log(status: String) {
        (application as? VirtualScreenApplication)?.activityLog?.runtime("accessibility_service", status)
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        mutableConnected.value = true
        log("connected")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { log("interrupted"); clear() }
    override fun onDestroy() {
        if (current === this) {
            current = null
            mutableConnected.value = false
        }
        log("disconnected")
        clear()
        super.onDestroy()
    }

    suspend fun snapshot(displayId: Int, observationId: String): NodeSnapshot = withContext(Dispatchers.Main) {
        require(displayId > 0) { "INVALID_DISPLAY" }
        clear()
        val job = currentCoroutineContext()
        val windows = getWindowsOnAllDisplays().get(displayId).orEmpty()
            .filter { it.displayId == displayId }
            .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.thenByDescending { it.layer })
        val roots = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        val windowSnapshots = mutableListOf<WindowSnapshot>()
        val result = mutableListOf<NodeInfo>()
        val truncationReasons = LinkedHashSet<String>()
        var complete = false
        try {
            for (window in windows) {
                job.ensureActive()
                val root = window.root
                if (root != null) roots += window.id to root
                windowSnapshots += WindowSnapshot(window.id, window.displayId, window.type, root?.packageName?.toString(),
                    window.title?.toString(), window.layer, window.isFocused, window.isActive)
            }
            if (roots.isEmpty()) {
                clearCache()
                error("NODE_WINDOW_NOT_READY")
            }
            val activePath = HashSet<AccessibilityNodeInfo>()
            suspend fun visit(node: AccessibilityNodeInfo, depth: Int, parentId: String?, windowId: Int) {
                job.ensureActive()
                if (result.size >= NODE_LIMIT) {
                    truncationReasons += "node_limit"
                    return
                }
                if (depth > DEPTH_LIMIT) {
                    truncationReasons += "depth_limit"
                    return
                }
                if (!activePath.add(node)) {
                    truncationReasons += "cycle_detected"
                    return
                }
                try {
                    val id = "$observationId:${result.size}"
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    val retainedNode = AccessibilityNodeInfo.obtain(node)
                    var retained = false
                    try {
                        saved[id] = Saved(retainedNode, signature(node), displayId)
                        retained = true
                    } finally {
                        if (!retained) retainedNode.recycle()
                    }
                    result += NodeInfo(
                        id = id,
                        text = if (node.isPassword) null else node.text?.toString(),
                        contentDescription = node.contentDescription?.toString(),
                        className = node.className?.toString(),
                        bounds = bounds,
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        resourceId = node.viewIdResourceName,
                        parentId = parentId,
                        enabled = node.isEnabled,
                        focused = node.isFocused,
                        selected = node.isSelected,
                        checkable = node.isCheckable,
                        checked = node.isChecked,
                        visible = node.isVisibleToUser,
                        password = node.isPassword,
                        hintText = node.hintText?.toString(),
                        stateDescription = if (node.isPassword) null else node.stateDescription?.toString(),
                        longClickable = node.isLongClickable,
                        focusable = node.isFocusable,
                        windowId = node.windowId.takeIf { it >= 0 } ?: windowId,
                        packageName = node.packageName?.toString(),
                        actionList = node.actionList.map { NodeActionInfo(it.id, it.label?.toString()) },
                    )
                    val childCount = node.childCount
                    if (childCount > CHILD_LIMIT) truncationReasons += "child_limit"
                    for (index in 0 until childCount.coerceAtMost(CHILD_LIMIT)) {
                        job.ensureActive()
                        if ("node_limit" in truncationReasons) break
                        if (result.size >= NODE_LIMIT) {
                            truncationReasons += "node_limit"
                            break
                        }
                        node.getChild(index)?.let { child ->
                            try { visit(child, depth + 1, id, windowId) } finally { child.recycle() }
                        }
                    }
                } finally {
                    activePath.remove(node)
                }
            }
            for (entry in roots) {
                job.ensureActive()
                if (result.size >= NODE_LIMIT) {
                    truncationReasons += "node_limit"
                    break
                }
                visit(entry.second, 1, null, entry.first)
            }
            val snapshot = NodeSnapshot(observationId, displayId, roots.first().second.packageName?.toString(), result,
                truncated = truncationReasons.isNotEmpty(), truncationReasons = truncationReasons.toList(), windows = windowSnapshots,
                nodeLimit = NODE_LIMIT, depthLimit = DEPTH_LIMIT, childLimit = CHILD_LIMIT)
            complete = true
            snapshot
        } finally {
            var rootCleanupFailed = false
            roots.forEach { (_, root) ->
                try { root.recycle() } catch (_: Exception) { rootCleanupFailed = true }
            }
            if (!complete || rootCleanupFailed) clear()
        }
    }

    suspend fun act(displayId: Int, operation: BackendOperation.NodeAction): BackendResult = withContext(Dispatchers.Main) {
        val item = saved[operation.nodeId] ?: return@withContext BackendResult.failure("STALE_OBSERVATION")
        val window = getWindowsOnAllDisplays().get(displayId).orEmpty().firstOrNull { it.id == item.node.windowId && it.displayId == displayId }
        if (displayId <= 0 || item.displayId != displayId || window == null || !item.node.refresh() || signature(item.node) != item.signature) return@withContext BackendResult.failure("STALE_OBSERVATION")
        val action = when (operation.action) {
            "click" -> AccessibilityNodeInfo.ACTION_CLICK
            "long_click" -> AccessibilityNodeInfo.ACTION_LONG_CLICK
            "focus" -> AccessibilityNodeInfo.ACTION_FOCUS
            "scroll_forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "scroll_backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "set_text" -> AccessibilityNodeInfo.ACTION_SET_TEXT
            else -> return@withContext BackendResult.failure("NODE_ACTION_UNSUPPORTED")
        }
        if (item.node.actionList.none { it.id == action }) return@withContext BackendResult.failure("NODE_ACTION_UNSUPPORTED")
        val args = Bundle().apply { if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, operation.value.orEmpty()) }
        if (item.node.performAction(action, args)) BackendResult.success("effect" to "unconfirmed", "requires_observation" to true)
        else BackendResult.failure("NODE_ACTION_REJECTED")
    }

    suspend fun setFocusedText(displayId: Int, text: String, replace: Boolean): BackendResult = withContext(Dispatchers.Main) {
        require(displayId > 0 && text.length <= 20_000 && '\u0000' !in text) { "INVALID_TEXT" }
        val roots = getWindowsOnAllDisplays().get(displayId).orEmpty().filter { it.displayId == displayId }.mapNotNull { it.root }
        try {
            val editor = roots.firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
                ?: return@withContext BackendResult.failure("FOCUSED_EDITOR_UNAVAILABLE")
            try {
                if (!editor.isEditable || !editor.isEnabled) return@withContext BackendResult.failure("FOCUSED_EDITOR_UNAVAILABLE")
                val old = editor.text?.toString().orEmpty()
                val start = editor.textSelectionStart.takeIf { it in 0..old.length } ?: old.length
                val end = editor.textSelectionEnd.takeIf { it in start..old.length } ?: start
                val value = if (replace) text else old.substring(0, start) + text + old.substring(end)
                val ok = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })
                if (ok) BackendResult.success("verified" to (editor.refresh() && editor.text?.toString() == value)) else BackendResult.failure("TEXT_INPUT_REJECTED")
            } finally { editor.recycle() }
        } finally { roots.forEach { it.recycle() } }
    }

    private fun signature(node: AccessibilityNodeInfo): String {
        // Insets and display transitions can move a live node by a few pixels
        // after the tree was captured. Geometry is observation data, not node
        // identity; keep the stable fields that protect against recycled or
        // semantically changed nodes.
        val actions = node.actionList.joinToString(",") { "${it.id}:${it.label}" }
        return listOf(node.windowId, node.packageName, node.viewIdResourceName, node.className,
            if (node.isPassword) null else node.text, node.contentDescription, node.hintText,
            if (node.isPassword) null else node.stateDescription, node.isEnabled, node.isClickable,
            node.isLongClickable, node.isEditable, node.isScrollable, node.isFocusable, node.isFocused,
            node.isSelected, node.isCheckable, node.isChecked, node.isVisibleToUser, node.isPassword, actions).joinToString("|")
    }
    fun clear() { saved.values.forEach { it.node.recycle() }; saved.clear() }
    companion object {
        private const val NODE_LIMIT = 5000
        private const val DEPTH_LIMIT = 100
        private const val CHILD_LIMIT = 1000
        @Volatile var current: ScreenAccessibilityService? = null
            private set
        private val mutableConnected = kotlinx.coroutines.flow.MutableStateFlow(false)
        val connected: kotlinx.coroutines.flow.StateFlow<Boolean> = mutableConnected.asStateFlow()
    }
}
