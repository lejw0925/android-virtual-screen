package io.github.lejw0925.virtualscreen

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import io.github.lejw0925.virtualscreen.mcp.McpToolProbe
import io.github.lejw0925.virtualscreen.ui.BackendProbeActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class McpToolProbeTest {
    private fun sessions(backend: VirtualScreenBackend = FakeBackend(BackendKind.ROOT)) =
        VirtualScreenSessionManager(RuntimeEnvironment.getApplication(), backend, FakeBackend(BackendKind.SHIZUKU))

    @Test
    fun probeWaitsForDisplayBoundsAndEditorFocusAndVerifiesTheExactCounter() = runBlocking {
        val report = probeWithAsyncControls(counterTapWorks = true)

        assertEquals(report.failedTools.toString(), 19, report.passedTools)
    }

    @Test
    fun successfulEditorTapCannotHideAnUnconfirmedCounterTap() = runBlocking {
        val report = probeWithAsyncControls(counterTapWorks = false)

        val tap = report.results.single { it.tool == "tap_at" }
        assertEquals(2, tap.attempts)
        assertFalse("A focus tap must not replace the counter effect check", tap.passed)
        assertEquals("PROBE_EFFECT_NOT_CONFIRMED", tap.code)
        assertTrue(report.failedTools.any { it.tool == "tap_at" })
        assertEquals(18, report.passedTools)
        assertEquals(CapabilityStatus.UNAVAILABLE, report.capabilities.inputInjection)
        assertTrue(report.results.single { it.tool == "input_text" }.passed)
    }

    @Test
    fun successfulEditorTapCannotHideAnUnacknowledgedCounterTap() = runBlocking {
        val report = probeWithAsyncControls(counterTapWorks = true, counterTapAcknowledged = false)

        val tap = report.results.single { it.tool == "tap_at" }
        assertFalse("An input timeout must remain visible even when its effect arrives", tap.passed)
        assertEquals("PROBE_INPUT_FAILED", tap.code)
        assertTrue(report.failedTools.any { it.tool == "tap_at" })
        assertEquals(18, report.passedTools)
        assertEquals(CapabilityStatus.UNAVAILABLE, report.capabilities.inputInjection)
        assertTrue(report.results.single { it.tool == "input_text" }.passed)
    }

    private suspend fun probeWithAsyncControls(
        counterTapWorks: Boolean,
        counterTapAcknowledged: Boolean = true,
    ): McpToolProbeReport {
        var reads = 0
        var counter = "0"
        var counterPending = 0
        var held = false
        var editorText = ""
        var editorFocused = false
        var focusPending = 0
        val frame = Bitmap.createBitmap(720, 1600, Bitmap.Config.ARGB_8888)
        val backend = object : VirtualScreenBackend by FakeBackend(BackendKind.ROOT) {
            override suspend fun observe(handle: VirtualScreenHandle) =
                Result.success(ScreenObservation("frame", handle.id, 0, 720, 1600, 0, frame))
            override suspend fun nodes(handle: VirtualScreenHandle, observationId: String): Result<NodeSnapshot> {
                reads++
                if (counterPending > 0 && --counterPending == 0) counter = "1"
                if (focusPending > 0 && --focusPending == 0) editorFocused = true
                val offset = if (reads < 5) 800 else 0
                return Result.success(NodeSnapshot(observationId, handle.displayId, "fixture", listOf(
                    NodeInfo("counter", counter, if (held) BackendProbeActivity.LONG_PRESSED else BackendProbeActivity.COUNTER,
                        bounds = Rect(48 + offset, 113, 672 + offset, 225), clickable = true),
                    NodeInfo("editor", editorText, BackendProbeActivity.EDITOR, bounds = Rect(48, 225, 672, 337), editable = true, focused = editorFocused),
                    NodeInfo("scroll", contentDescription = BackendProbeActivity.SCROLL, bounds = Rect(48, 337, 672, 1552), scrollable = true),
                    NodeInfo("unrelated", text = "1"),
                )))
            }
            override suspend fun operate(handle: VirtualScreenHandle, operation: BackendOperation): BackendResult {
                when (operation) {
                    is BackendOperation.Tap -> {
                        assertEquals(360, operation.x)
                        if (operation.y < 225) {
                            if (counterTapWorks) counterPending = 4
                            if (!counterTapAcknowledged) return BackendResult.failure("UI_INPUT_TIMEOUT")
                        } else focusPending = 4
                    }
                    is BackendOperation.LongPress -> held = true
                    is BackendOperation.NodeAction -> {
                        assertEquals("set_text", operation.action)
                        editorText = operation.value.orEmpty()
                    }
                    is BackendOperation.Text -> {
                        assertTrue("Input must wait for focused editor", editorFocused)
                        editorText = operation.value
                    }
                    else -> Unit
                }
                return BackendResult.success()
            }
        }
        val manager = sessions(backend)

        val report = McpToolProbe(manager).run()

        assertNull(manager.state.value.displayId)
        assertNull(manager.leaseStatus())
        frame.recycle()
        return report
    }

    @Test
    fun occupiedDisplayIsNotClosedOrReplacedByTheProbe() = runBlocking {
        val backend = FakeBackend(BackendKind.ROOT)
        val manager = sessions(backend)
        val lease = (manager.acquireLease("agent") as LeaseDecision.Granted).lease
        manager.create(lease.owner, lease.token)
        manager.releaseLease(lease.owner, lease.token)

        val report = McpToolProbe(manager).run()

        assertEquals(7, manager.state.value.displayId)
        assertEquals(0, backend.closed)
        assertEquals(19, report.totalTools)
        assertTrue(report.failedTools.any { it.code == "DISPLAY_ALREADY_ACTIVE" })
        assertNull(manager.leaseStatus())
    }

    @Test
    fun failedCreationStillAccountsForEveryToolAndReleasesControl() = runBlocking {
        val backend = object : VirtualScreenBackend by FakeBackend(BackendKind.ROOT) {
            override suspend fun create(config: VirtualScreenConfig) =
                Result.failure<VirtualScreenHandle>(IllegalStateException("DISPLAY_CREATE_FAILED"))
        }
        val manager = sessions(backend)

        val report = McpToolProbe(manager).run()

        assertEquals(19, report.totalTools)
        assertTrue(report.results.any { it.tool == "create_screen" && it.code == "DISPLAY_CREATE_FAILED" })
        assertEquals(CapabilityStatus.UNAVAILABLE, report.capabilities.displayCreation)
        assertEquals(CapabilityStatus.UNKNOWN, report.capabilities.inputInjection)
        assertNull(manager.state.value.displayId)
        assertNull(manager.leaseStatus())
    }

    @Test
    fun cancellationReleasesTheProbeLease() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val backend = object : VirtualScreenBackend by FakeBackend(BackendKind.ROOT) {
            override suspend fun create(config: VirtualScreenConfig): Result<VirtualScreenHandle> {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
                error("unreachable")
            }
        }
        val manager = sessions(backend)
        val run = async { McpToolProbe(manager).run() }
        entered.await()
        run.cancel()
        run.join()

        assertNull(manager.state.value.displayId)
        assertNull(manager.leaseStatus())
    }
}
