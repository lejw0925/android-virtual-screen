package io.github.lejw0925.virtualscreen

import android.app.Application
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.github.lejw0925.virtualscreen.backend.NodeActionInfo
import io.github.lejw0925.virtualscreen.backend.NodeInfo
import io.github.lejw0925.virtualscreen.backend.NodeSnapshot
import io.github.lejw0925.virtualscreen.backend.WindowSnapshot
import io.github.lejw0925.virtualscreen.mcp.NodeTreeFormatter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NodeTreeFormatterTest {
    @Test fun compactKeepsReadableAndInteractiveNodesAndDerivesSafeContainerLabels() {
        val snapshot = snapshot(
            NodeInfo("root", resourceId = "fixture:id/root", windowId = 7),
            NodeInfo("container", parentId = "root", clickable = true, windowId = 7, packageName = "fixture"),
            NodeInfo("text-a", text = "  Open   file ", parentId = "container", windowId = 7),
            NodeInfo("text-b", text = "Open file", parentId = "container", windowId = 7),
            NodeInfo("nested", text = "Nested action", parentId = "container", clickable = true, windowId = 7),
            NodeInfo("nested-child", text = "Do not absorb", parentId = "nested", windowId = 7),
            NodeInfo("action-container", actionList = listOf(NodeActionInfo(AccessibilityNodeInfo.ACTION_CLICK, "Activate")),
                parentId = "container", windowId = 7),
            NodeInfo("action-text", text = "Do not merge", parentId = "action-container", windowId = 7),
            NodeInfo("password", text = "secret", stateDescription = "secret state", hintText = "Password", password = true,
                editable = true, parentId = "container", windowId = 7),
            NodeInfo("hint", hintText = "Search", editable = true, parentId = "root", windowId = 7),
            NodeInfo("state", stateDescription = "Expanded", selected = true, parentId = "root", windowId = 7),
            NodeInfo("long", longClickable = true, actionList = listOf(NodeActionInfo(AccessibilityNodeInfo.ACTION_LONG_CLICK, "Hold")), parentId = "root", windowId = 7),
            NodeInfo("disabled", text = "Disabled content", enabled = false, parentId = "root", windowId = 7),
            NodeInfo("invisible", text = "Hidden", visible = false, parentId = "root", windowId = 7),
            NodeInfo("resource-only", resourceId = "fixture:id/structure", parentId = "root", windowId = 7),
        )

        val result = NodeTreeFormatter.values(snapshot, "compact")
        val nodes = result["nodes"] as List<Map<String, Any?>>
        val byId = nodes.associateBy { it.getValue("id") as String }

        assertEquals(15, result["node_count"])
        assertEquals(nodes.size, result["returned_node_count"])
        assertEquals(7, result["display_id"])
        assertEquals("fixture", byId.getValue("container")["package"])
        assertNull(byId.getValue("container")["parent_id"])
        assertEquals("container", byId.getValue("text-a")["parent_id"])
        assertEquals("Open file", byId.getValue("container")["label"])
        assertTrue(byId.getValue("container").containsKey("clickable"))
        assertTrue(byId.getValue("nested").containsKey("clickable"))
        assertEquals("nested", byId.getValue("nested-child")["parent_id"])
        assertEquals("Do not merge", byId.getValue("action-container")["label"])
        assertEquals("action-container", byId.getValue("action-text")["parent_id"])
        assertFalse((byId.getValue("container")["label"] as String).contains("Do not merge"))
        assertEquals(listOf("long_click"), byId.getValue("long")["actions"])
        assertEquals(listOf("click"), byId.getValue("action-container")["actions"])
        assertEquals("Search", byId.getValue("hint")["hint_text"])
        assertEquals("Expanded", byId.getValue("state")["state_description"])
        assertEquals(false, byId.getValue("disabled")["enabled"])
        assertFalse(byId.containsKey("invisible"))
        assertFalse(byId.containsKey("resource-only"))
        assertFalse(byId.getValue("password").containsKey("text"))
        assertFalse(byId.getValue("password").containsKey("state_description"))
        assertFalse((byId.getValue("container")["label"] as String).contains("Nested"))
        assertFalse((byId.getValue("container")["label"] as String).contains("secret"))

        val windows = result["windows"] as List<Map<String, Any?>>
        assertEquals(7, windows.single()["id"])
        assertEquals(7, windows.single()["display_id"])
    }

    @Test fun fullReturnsEveryCollectedNodeWithRawRelationsAndCompleteFields() {
        val snapshot = snapshot(
            NodeInfo("structure", resourceId = "fixture:id/root", enabled = true, visible = false, windowId = 7),
            NodeInfo("child", text = "value", parentId = "structure", bounds = Rect(1, 2, 3, 4),
                focused = true, actionList = listOf(NodeActionInfo(AccessibilityNodeInfo.ACTION_CLICK, "Activate")), windowId = 7),
        ).copy(truncated = true, truncationReasons = listOf("child_limit"))

        val result = NodeTreeFormatter.values(snapshot, "full")
        val nodes = result["nodes"] as List<Map<String, Any?>>
        val child = nodes[1]

        assertEquals(2, result["node_count"])
        assertEquals(2, result["returned_node_count"])
        assertEquals(2, nodes.size)
        assertEquals("structure", child["parent_id"])
        assertEquals(listOf(1, 2, 3, 4), child["bounds"])
        assertEquals(true, child["enabled"])
        assertEquals(false, child["long_clickable"])
        assertEquals(true, child["visible"])
        assertEquals(false, nodes[0]["visible"])
        assertTrue(child.containsKey("text"))
        assertTrue(child.containsKey("state_description"))
        assertTrue(child.containsKey("resource_id"))
        assertEquals(listOf("click"), child["actions"])
        assertEquals(mapOf("id" to AccessibilityNodeInfo.ACTION_CLICK, "label" to "Activate"),
            (child["action_list"] as List<*>).single())
        assertFalse(child.containsKey("label"))
        assertEquals(listOf("child_limit"), result["truncation_reasons"])
        assertEquals(5000, result["node_limit"])
        assertEquals(100, result["depth_limit"])
        assertEquals(1000, result["child_limit"])

        val compact = JSONObject(NodeTreeFormatter.values(snapshot, "compact")).toString()
        val compactNodes = NodeTreeFormatter.values(snapshot, "compact")["nodes"] as List<Map<String, Any?>>
        assertEquals(1, compactNodes.size)
        assertNull(compactNodes.single()["parent_id"])
        val full = JSONObject(result).toString()
        assertTrue(compact.length < full.length)
    }

    @Test fun compactParentRemappingStopsAtWindowBoundariesAndPasswordsNeverSupplyNames() {
        val snapshot = snapshot(
            NodeInfo("outer", clickable = true, windowId = 7),
            NodeInfo("other-window", text = "Other window", parentId = "outer", windowId = 8),
            NodeInfo("password-container", clickable = true, parentId = "outer", windowId = 7),
            NodeInfo("password-child", text = "private", parentId = "password-container", password = true, editable = true, windowId = 7),
            NodeInfo("resource-parent", resourceId = "fixture:id/group", parentId = "outer", windowId = 7),
            NodeInfo("leaf", text = "Visible", parentId = "resource-parent", windowId = 7),
        )

        val nodes = NodeTreeFormatter.values(snapshot, "compact")["nodes"] as List<Map<String, Any?>>
        val byId = nodes.associateBy { it.getValue("id") as String }
        assertEquals("Visible", byId.getValue("outer")["label"])
        assertEquals("outer", byId.getValue("leaf")["parent_id"])
        assertNull(byId.getValue("other-window")["parent_id"])
        assertFalse((byId.getValue("outer")["label"] as String).contains("Other"))
        assertFalse((byId.getValue("password-container").getOrDefault("label", "") as String).contains("private"))
        assertFalse(byId.getValue("password-child").containsKey("text"))
    }

    private fun snapshot(vararg nodes: NodeInfo) = NodeSnapshot(
        observationId = "observation",
        displayId = 7,
        packageName = "fixture",
        nodes = nodes.toList(),
        windows = listOf(WindowSnapshot(7, 7, 1, "fixture", "Fixture", 4, focused = true, active = true)),
    )
}
