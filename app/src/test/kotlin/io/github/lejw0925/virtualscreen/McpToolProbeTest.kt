package io.github.lejw0925.virtualscreen

import android.app.Application
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import io.github.lejw0925.virtualscreen.mcp.McpToolProbe
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
