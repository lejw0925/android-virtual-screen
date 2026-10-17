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
import io.github.lejw0925.virtualscreen.backend.NodeSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Every window lookup includes the owned virtual display, including focused text. */
class ScreenAccessibilityService : AccessibilityService() {
    private data class Saved(val node: AccessibilityNodeInfo, val signature: String, val displayId: Int)
    private val saved = mutableMapOf<String, Saved>()
    override fun onServiceConnected() { current = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { clear() }
    override fun onDestroy() { if (current === this) current = null; clear(); super.onDestroy() }

    suspend fun snapshot(displayId: Int, observationId: String): NodeSnapshot = withContext(Dispatchers.Main) {
        require(displayId > 0) { "INVALID_DISPLAY" }
        clear()
        val windows = getWindowsOnAllDisplays().get(displayId).orEmpty()
        // An IME can be above the app; include each owned-display window, app first.
        val roots=windows.filter { it.displayId == displayId }.sortedWith(compareByDescending<AccessibilityWindowInfo> { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.thenByDescending { it.layer }).mapNotNull { it.root }
        check(roots.isNotEmpty()) { "ACCESSIBILITY_NODES_UNAVAILABLE" }
        val result = mutableListOf<NodeInfo>()
        var truncated = false
        fun visit(node: AccessibilityNodeInfo, depth: Int, parentId: String?) {
            if (result.size >= 500 || depth > 40) { truncated = true; return }
            val id = "$observationId:${result.size}"
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            saved[id] = Saved(AccessibilityNodeInfo.obtain(node), signature(node), displayId)
            result += NodeInfo(id, if (node.isPassword) null else node.text?.toString(), node.contentDescription?.toString(), node.className?.toString(), bounds,
                node.isClickable, node.isEditable, node.isScrollable, node.viewIdResourceName, parentId,
                node.isEnabled, node.isFocused, node.isSelected, node.isCheckable, node.isChecked, node.isVisibleToUser, node.isPassword)
            if (node.childCount > 100) truncated = true
            for (index in 0 until node.childCount.coerceAtMost(100)) node.getChild(index)?.let { child -> try { visit(child, depth + 1, id) } finally { child.recycle() } }
        }
        val pkg = roots.first().packageName?.toString()
        try { roots.forEach { visit(it,0,null) } } finally { roots.forEach { it.recycle() } }
        NodeSnapshot(observationId, displayId, pkg, result, truncated)
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
        return listOf(node.windowId, node.viewIdResourceName, node.className, node.text, node.contentDescription,
            node.isEnabled, node.isClickable, node.isEditable, node.isScrollable).joinToString("|")
    }
    fun clear() { saved.values.forEach { it.node.recycle() }; saved.clear() }
    companion object { @Volatile var current: ScreenAccessibilityService? = null; private set }
}
