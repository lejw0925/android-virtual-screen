package io.github.lejw0925.virtualscreen.mcp

import android.view.accessibility.AccessibilityNodeInfo
import io.github.lejw0925.virtualscreen.backend.NodeInfo
import io.github.lejw0925.virtualscreen.backend.NodeSnapshot

/** Formats one collected tree without changing the source used by selectors. */
internal object NodeTreeFormatter {
    private val actionNames = linkedMapOf(
        AccessibilityNodeInfo.ACTION_CLICK to "click",
        AccessibilityNodeInfo.ACTION_LONG_CLICK to "long_click",
        AccessibilityNodeInfo.ACTION_FOCUS to "focus",
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD to "scroll_forward",
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD to "scroll_backward",
        AccessibilityNodeInfo.ACTION_SET_TEXT to "set_text",
    )

    fun values(snapshot: NodeSnapshot, format: String): Map<String, Any?> {
        require(format == "compact" || format == "full")
        val compact = format == "compact"
        val included = if (compact) snapshot.nodes.filter(::isUseful) else snapshot.nodes
        val retainedIds = included.mapTo(HashSet()) { it.id }
        val nodesById = snapshot.nodes.associateBy { it.id }
        val labels = if (compact) deriveLabels(snapshot.nodes) else emptyMap()
        val nodeValues = included.map { node ->
            if (compact) compactNode(node, retainedIds, nodesById, labels[node.id]) else fullNode(node)
        }
        return linkedMapOf(
            "display_id" to snapshot.displayId,
            "windows" to snapshot.windows.map(::windowValue),
            "nodes" to nodeValues,
            "package" to snapshot.packageName,
            "node_count" to snapshot.nodes.size,
            "returned_node_count" to included.size,
            "truncated" to snapshot.truncated,
            "truncation_reasons" to snapshot.truncationReasons,
            "format" to format,
            "node_limit" to snapshot.nodeLimit,
            "depth_limit" to snapshot.depthLimit,
            "child_limit" to snapshot.childLimit,
        )
    }

    private fun isUseful(node: NodeInfo): Boolean {
        if (!node.visible) return false
        val readable = safeText(node).any { !it.isNullOrBlank() }
        return readable || isInteractive(node) || node.focused || node.selected || supportedActions(node).isNotEmpty()
    }

    private fun isInteractive(node: NodeInfo): Boolean =
        node.clickable || node.longClickable || node.editable || node.scrollable || node.focusable || node.checkable ||
            supportedActions(node).isNotEmpty()

    private fun supportedActions(node: NodeInfo): List<String> = node.actionList.mapNotNull { actionNames[it.id] }.distinct()

    private fun safeText(node: NodeInfo): List<String?> = listOf(
        if (node.password) null else node.text,
        node.contentDescription,
        node.hintText,
        if (node.password) null else node.stateDescription,
    )

    private fun compactNode(
        node: NodeInfo,
        retainedIds: Set<String>,
        nodesById: Map<String, NodeInfo>,
        label: String?,
    ): Map<String, Any?> {
        val values = linkedMapOf<String, Any?>("id" to node.id)
        putIfNotBlank(values, "text", if (node.password) null else node.text)
        putIfNotBlank(values, "description", node.contentDescription)
        putIfNotBlank(values, "hint_text", node.hintText)
        putIfNotBlank(values, "state_description", if (node.password) null else node.stateDescription)
        putIfNotBlank(values, "label", label)
        putIfNotBlank(values, "class", node.className)
        putIfNotBlank(values, "resource_id", node.resourceId)
        values["parent_id"] = nearestRetainedParent(node, retainedIds, nodesById)
        node.bounds?.let { values["bounds"] = listOf(it.left, it.top, it.right, it.bottom) }
        node.windowId?.let { values["window_id"] = it }
        putIfNotBlank(values, "package", node.packageName)
        putTrue(values, "clickable", node.clickable)
        putTrue(values, "long_clickable", node.longClickable)
        putTrue(values, "editable", node.editable)
        putTrue(values, "scrollable", node.scrollable)
        putTrue(values, "focusable", node.focusable)
        if (!node.enabled) values["enabled"] = false
        putTrue(values, "focused", node.focused)
        putTrue(values, "selected", node.selected)
        putTrue(values, "checkable", node.checkable)
        putTrue(values, "checked", node.checked)
        putTrue(values, "password", node.password)
        val actions = supportedActions(node)
        if (actions.isNotEmpty()) values["actions"] = actions
        return values
    }

    private fun fullNode(node: NodeInfo): Map<String, Any?> = linkedMapOf<String, Any?>(
        "id" to node.id,
        "window_id" to node.windowId,
        "package" to node.packageName,
        "text" to if (node.password) null else node.text,
        "description" to node.contentDescription,
        "hint_text" to node.hintText,
        "state_description" to if (node.password) null else node.stateDescription,
        "class" to node.className,
        "resource_id" to node.resourceId,
        "parent_id" to node.parentId,
        "bounds" to node.bounds?.let { listOf(it.left, it.top, it.right, it.bottom) },
        "clickable" to node.clickable,
        "long_clickable" to node.longClickable,
        "editable" to node.editable,
        "scrollable" to node.scrollable,
        "focusable" to node.focusable,
        "enabled" to node.enabled,
        "focused" to node.focused,
        "selected" to node.selected,
        "checkable" to node.checkable,
        "checked" to node.checked,
        "visible" to node.visible,
        "password" to node.password,
        "action_list" to node.actionList.map { linkedMapOf("id" to it.id, "label" to it.label) },
    ).apply {
        supportedActions(node).takeIf { it.isNotEmpty() }?.let { put("actions", it) }
    }

    private fun nearestRetainedParent(node: NodeInfo, retainedIds: Set<String>, byId: Map<String, NodeInfo>): String? {
        val seen = HashSet<String>()
        var parentId = node.parentId
        while (parentId != null && seen.add(parentId)) {
            val parent = byId[parentId] ?: return null
            if (parent.windowId != node.windowId) return null
            if (parent.id in retainedIds) return parent.id
            parentId = parent.parentId
        }
        return null
    }

    private fun deriveLabels(nodes: List<NodeInfo>): Map<String, String> {
        val children = nodes.groupBy { it.parentId }
        val passwordSubtree = HashSet<String>()
        val pending = ArrayDeque(nodes.filter { it.password })
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (!passwordSubtree.add(node.id)) continue
            children[node.id].orEmpty().forEach(pending::addLast)
        }
        return nodes.asSequence()
            .filter { it.visible && it.id !in passwordSubtree && isInteractive(it) && ownNames(it).none { name -> !name.isNullOrBlank() } }
            .mapNotNull { container ->
                val parts = LinkedHashSet<String>()
                val path = HashSet<String>()
                fun collect(parent: NodeInfo) {
                    if (!path.add(parent.id)) return
                    for (child in children[parent.id].orEmpty()) {
                        if (!child.visible || child.password || child.windowId != container.windowId) continue
                        if (isInteractive(child)) continue
                        ownNames(child).filterNotNull().forEach { name -> normalize(name).takeIf(String::isNotEmpty)?.let(parts::add) }
                        collect(child)
                    }
                    path.remove(parent.id)
                }
                collect(container)
                parts.joinToString(" ").takeIf(String::isNotBlank)?.let { container.id to it }
            }
            .toMap()
    }

    private fun ownNames(node: NodeInfo): List<String?> = listOf(
        if (node.password) null else node.text,
        node.contentDescription,
        node.hintText,
    )

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun windowValue(window: io.github.lejw0925.virtualscreen.backend.WindowSnapshot): Map<String, Any?> = linkedMapOf(
        "id" to window.id,
        "display_id" to window.displayId,
        "type" to window.type,
        "package" to window.packageName,
        "title" to window.title,
        "layer" to window.layer,
        "focused" to window.focused,
        "active" to window.active,
    )

    private fun putIfNotBlank(values: MutableMap<String, Any?>, key: String, value: String?) {
        if (!value.isNullOrBlank()) values[key] = value
    }

    private fun putTrue(values: MutableMap<String, Any?>, key: String, value: Boolean) {
        if (value) values[key] = true
    }
}
