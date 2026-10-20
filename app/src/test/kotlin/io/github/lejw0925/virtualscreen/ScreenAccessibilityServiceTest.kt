package io.github.lejw0925.virtualscreen

import android.app.Application
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.lejw0925.virtualscreen.core.ScreenAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadows.ShadowAccessibilityWindowInfo
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenAccessibilityServiceTest {
    @Before fun setMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetMainDispatcher() { Dispatchers.resetMain() }

    @Test fun capturesOnlyRequestedDisplayAndPreservesWindowOwnership() = runBlocking {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val appRoot = node("app root", "fixture")
        val dialogRoot = node("dialog root", "fixture")
        val phoneRoot = node("phone root", "phone.app")
        val appWindow = window(11, 7, AccessibilityWindowInfo.TYPE_APPLICATION, 1, appRoot, focused = true, active = true)
        val dialogWindow = window(12, 7, AccessibilityWindowInfo.TYPE_SYSTEM, 2, dialogRoot)
        val phoneWindow = window(13, 0, AccessibilityWindowInfo.TYPE_APPLICATION, 3, phoneRoot)
        shadowOf(service).setWindowsOnDisplay(7, listOf(dialogWindow, appWindow, phoneWindow))

        val snapshot = service.snapshot(7, "obs")

        assertEquals(2, snapshot.windows.size)
        assertEquals(listOf(11, 12), snapshot.windows.map { it.id })
        assertEquals(2, snapshot.nodes.size)
        assertTrue(snapshot.nodes.all { it.windowId in setOf(11, 12) })
        assertFalse(snapshot.nodes.any { it.packageName == "phone.app" })
        assertEquals(11, snapshot.windows.first().id)
        assertTrue(snapshot.windows.first().focused)
        assertTrue(snapshot.windows.first().active)
        service.onDestroy()
    }

    @Test fun reportsExpandedNodeBudgetAndEveryAppliedLimit() = runBlocking {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val windows = (0 until 6).map { windowIndex ->
            val root = node("root $windowIndex", "fixture")
            val rootShadow = Shadow.extract(root) as ShadowAccessibilityNodeInfo
            repeat(999) { index -> rootShadow.addChild(node("item $windowIndex-$index", "fixture")) }
            window(11 + windowIndex, 7, AccessibilityWindowInfo.TYPE_APPLICATION, windowIndex, root)
        }
        shadowOf(service).setWindowsOnDisplay(7, windows)

        val snapshot = service.snapshot(7, "obs")

        assertEquals(5000, snapshot.nodes.size)
        assertTrue(snapshot.truncated)
        assertEquals(listOf("node_limit"), snapshot.truncationReasons)
        assertEquals(5000, snapshot.nodeLimit)
        assertEquals(100, snapshot.depthLimit)
        assertEquals(1000, snapshot.childLimit)
        service.onDestroy()
    }

    @Test fun recordsDepthTruncationReason() = runBlocking {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val root = node("root", "fixture")
        var parent = root
        repeat(101) { index ->
            val child = node("chain $index", "fixture")
            (Shadow.extract(parent) as ShadowAccessibilityNodeInfo).addChild(child)
            parent = child
        }
        shadowOf(service).setWindowsOnDisplay(7, listOf(window(11, 7, AccessibilityWindowInfo.TYPE_APPLICATION, 1, root)))

        val snapshot = service.snapshot(7, "obs")

        assertTrue(snapshot.truncated)
        assertTrue("depth_limit" in snapshot.truncationReasons)
        assertEquals(5000, snapshot.nodeLimit)
        service.onDestroy()
    }

    @Test fun recordsPerNodeChildLimit() = runBlocking {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val root = node("root", "fixture")
        val rootShadow = Shadow.extract(root) as ShadowAccessibilityNodeInfo
        repeat(1001) { index -> rootShadow.addChild(node("item $index", "fixture")) }
        shadowOf(service).setWindowsOnDisplay(7, listOf(window(11, 7, AccessibilityWindowInfo.TYPE_APPLICATION, 1, root)))

        val snapshot = service.snapshot(7, "obs")

        assertEquals(1001, snapshot.nodes.size)
        assertTrue(snapshot.truncated)
        assertEquals(listOf("child_limit"), snapshot.truncationReasons)
        service.onDestroy()
    }

    @Test fun protectsAgainstAccessibilityNodeCycles() = runBlocking {
        val service = Robolectric.buildService(ScreenAccessibilityService::class.java).create().get()
        val root = node("root", "fixture")
        val child = node("child", "fixture")
        (Shadow.extract(root) as ShadowAccessibilityNodeInfo).addChild(child)
        (Shadow.extract(child) as ShadowAccessibilityNodeInfo).addChild(root)
        shadowOf(service).setWindowsOnDisplay(7, listOf(window(11, 7, AccessibilityWindowInfo.TYPE_APPLICATION, 1, root)))

        val snapshot = service.snapshot(7, "obs")

        assertEquals(2, snapshot.nodes.size)
        assertTrue(snapshot.truncated)
        assertTrue("cycle_detected" in snapshot.truncationReasons)
        service.onDestroy()
    }

    private fun node(text: String, packageName: String): AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {
            setPackageName(packageName)
            setText(text)
            setBoundsInScreen(Rect(0, 0, 100, 40))
        }

    private fun window(
        id: Int,
        displayId: Int,
        type: Int,
        layer: Int,
        root: AccessibilityNodeInfo?,
        focused: Boolean = false,
        active: Boolean = false,
    ): AccessibilityWindowInfo = AccessibilityWindowInfo.obtain().also { window ->
        val shadow = Shadow.extract(window) as ShadowAccessibilityWindowInfo
        shadow.setId(id)
        shadow.setDisplayId(displayId)
        shadow.setType(type)
        shadow.setLayer(layer)
        shadow.setTitle("Window $id")
        shadow.setFocused(focused)
        shadow.setActive(active)
        root?.let(shadow::setRoot)
    }
}
